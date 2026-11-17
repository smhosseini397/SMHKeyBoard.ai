package com.farshekashan.keyfarsi

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/** Minimal SentencePiece Unigram reader for the official ALBERT Persian spiece.model. */
internal class SentencePieceUnigramTokenizer(
    modelBytes: ByteArray,
    private val clsId: Int,
    private val sepId: Int,
    private val maskId: Int,
    private val unkId: Int,
    private val padId: Int
) {
    private data class Piece(val text: String, val score: Float, val id: Int)
    private class Node {
        val next = HashMap<Char, Node>()
        var terminal: Piece? = null
    }
    private val root = Node()

    init {
        val pieces = parsePieces(modelBytes)
        require(pieces.isNotEmpty()) { "SentencePiece model contains no pieces" }
        pieces.forEach { piece ->
            var node = root
            piece.text.forEach { ch -> node = node.next.getOrPut(ch) { Node() } }
            node.terminal = piece
        }
    }

    fun encode(text: String, maxLength: Int = 64): LongArray {
        val ids = encodePieces(text).take((maxLength - 2).coerceAtLeast(1))
        return (listOf(clsId) + ids + sepId).map { it.toLong() }.toLongArray()
    }

    fun encodePieces(text: String): List<Int> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        val chars = ("▁" + normalized.replace(' ', '▁')).toCharArray()
        val best = DoubleArray(chars.size + 1) { Double.NEGATIVE_INFINITY }
        val back = arrayOfNulls<Piece>(chars.size + 1)
        val previous = IntArray(chars.size + 1) { -1 }
        best[0] = 0.0
        for (i in chars.indices) {
            if (!best[i].isFinite()) continue
            var node = root
            var j = i
            while (j < chars.size) {
                node = node.next[chars[j]] ?: break
                j++
                val piece = node.terminal ?: continue
                val score = best[i] + piece.score
                if (score > best[j]) {
                    best[j] = score
                    back[j] = piece
                    previous[j] = i
                }
            }
            // SentencePiece's unknown token fallback: preserve an otherwise unmatched character.
            if (i + 1 <= chars.size && best[i] - 1000.0 > best[i + 1]) {
                best[i + 1] = best[i] - 1000.0
                back[i + 1] = Piece(chars[i].toString(), -1000f, unkId)
                previous[i + 1] = i
            }
        }
        val output = ArrayList<Int>()
        var at = chars.size
        while (at > 0) {
            val piece = back[at] ?: return listOf(unkId)
            output += piece.id
            at = previous[at]
            if (at < 0) return listOf(unkId)
        }
        output.reverse()
        return output
    }

    fun specialClsId() = clsId
    fun specialSepId() = sepId
    fun specialMaskId() = maskId
    fun specialPadId() = padId

    private fun normalize(value: String): String {
        val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace('\u064a', '\u06cc').replace('\u0643', '\u06a9')
            .replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        return nfkc
    }

    private fun parsePieces(bytes: ByteArray): List<Piece> {
        val result = ArrayList<Piece>()
        val input = ByteArrayInputStream(bytes)
        var id = 0
        while (input.available() > 0) {
            val tag = readVarint(input).toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 1 && wire == 2) {
                val len = readVarint(input).toInt()
                val payload = ByteArray(len)
                if (input.read(payload) != len) break
                val parsed = parsePiece(payload, id)
                if (parsed != null) { result += parsed; id++ }
            } else skipField(input, wire)
        }
        return result
    }

    private fun parsePiece(bytes: ByteArray, id: Int): Piece? {
        val input = ByteArrayInputStream(bytes)
        var text: String? = null
        var score = 0f
        while (input.available() > 0) {
            val tag = readVarint(input).toInt()
            when (tag ushr 3) {
                1 -> if ((tag and 7) == 2) {
                    val len = readVarint(input).toInt(); val b = ByteArray(len)
                    if (input.read(b) == len) text = String(b, Charsets.UTF_8)
                } else skipField(input, tag and 7)
                2 -> if ((tag and 7) == 5) {
                    val b = ByteArray(4); if (input.read(b) == 4) score = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float
                } else skipField(input, tag and 7)
                else -> skipField(input, tag and 7)
            }
        }
        return text?.let { Piece(it, score, id) }
    }

    private fun readVarint(input: ByteArrayInputStream): Long {
        var result = 0L; var shift = 0
        while (shift < 64) {
            val b = input.read(); if (b < 0) break
            result = result or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    private fun skipField(input: ByteArrayInputStream, wire: Int) {
        when (wire) {
            0 -> readVarint(input)
            1 -> input.skip(8)
            2 -> input.skip(readVarint(input))
            5 -> input.skip(4)
            else -> throw IllegalArgumentException("Unsupported protobuf wire type $wire")
        }
    }
}
