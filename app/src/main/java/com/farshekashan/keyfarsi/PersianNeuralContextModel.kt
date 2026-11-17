package com.farshekashan.keyfarsi

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max

/**
 * Tiny on-device neural reranker for Persian typo correction.
 *
 * This is a real, trainable one-hidden-layer MLP (not a pretrained general-purpose LLM).
 * It learns from explicit user corrections and ranks spelling candidates using the typed
 * form, candidate character n-grams, and the two preceding words. All weights stay local.
 */
internal object PersianNeuralContextModel {
    private const val INPUTS = 32
    private const val HIDDEN = 12
    private const val MODEL_KEY = "fa_neural_context_mlp_v1"
    private const val EXAMPLES_KEY = "fa_neural_context_examples_v1"
    private const val MAX_EXAMPLES = 100_000

    private data class Weights(
        val w1: Array<DoubleArray>, val b1: DoubleArray,
        val w2: DoubleArray, var b2: Double, var examples: Int
    )

    private fun initial(): Weights {
        // Deterministic tiny initialization; model has no random network dependency.
        val w1 = Array(HIDDEN) { h -> DoubleArray(INPUTS) { i ->
            val v = ((h * 37 + i * 17 + 13) % 23) - 11
            v / 2500.0
        } }
        val w2 = DoubleArray(HIDDEN) { h -> (((h * 11 + 5) % 17) - 8) / 1000.0 }
        return Weights(w1, DoubleArray(HIDDEN), w2, 0.0, 0)
    }

    private fun load(prefs: SharedPreferences): Weights {
        val raw = prefs.getString(MODEL_KEY, null) ?: return initial()
        return try {
            val o = JSONObject(raw)
            val a = o.getJSONArray("w1")
            val w1 = Array(HIDDEN) { h -> DoubleArray(INPUTS) { i -> a.getJSONArray(h).optDouble(i, 0.0) } }
            val b1a = o.getJSONArray("b1")
            val b1 = DoubleArray(HIDDEN) { b1a.optDouble(it, 0.0) }
            val w2a = o.getJSONArray("w2")
            val w2 = DoubleArray(HIDDEN) { w2a.optDouble(it, 0.0) }
            Weights(w1, b1, w2, o.optDouble("b2", 0.0), o.optInt("examples", 0))
        } catch (_: Exception) { initial() }
    }

    private fun save(prefs: SharedPreferences, m: Weights) {
        val w1 = JSONArray(); m.w1.forEach { row -> w1.put(JSONArray().also { a -> row.forEach { a.put(it) } }) }
        val b1 = JSONArray(); m.b1.forEach { b1.put(it) }
        val w2 = JSONArray(); m.w2.forEach { w2.put(it) }
        val obj = JSONObject().put("w1", w1).put("b1", b1).put("w2", w2)
            .put("b2", m.b2).put("examples", m.examples)
        prefs.edit().putString(MODEL_KEY, obj.toString()).putInt(EXAMPLES_KEY, m.examples).apply()
    }

    private fun norm(s: String) = PersianSpellChecker.normalize(s)

    private fun hashBucket(value: String, buckets: Int): Pair<Int, Double> {
        var h = 0x811c9dc5.toInt()
        value.forEach { ch -> h = (h xor ch.code) * 16777619 }
        val bucket = (h and Int.MAX_VALUE) % buckets
        return bucket to if ((h ushr 1) and 1 == 0) 1.0 else -1.0
    }

    private fun features(typedRaw: String, candidateRaw: String, previous: List<String>, distance: Int): DoubleArray {
        val x = DoubleArray(INPUTS)
        val typed = norm(typedRaw); val candidate = norm(candidateRaw)
        // Candidate character trigrams (hashed, signed feature bag).
        val padded = "^$candidate$"
        for (i in 0..(padded.length - 3).coerceAtLeast(-1)) {
            if (i + 3 <= padded.length) {
                val (bucket, sign) = hashBucket("c:${padded.substring(i, i + 3)}", 12)
                x[bucket] += sign / max(1, padded.length - 2).toDouble()
            }
        }
        val p0 = previous.getOrNull(0)?.let(::norm).orEmpty()
        val p1 = previous.getOrNull(1)?.let(::norm).orEmpty()
        val contextTokens = listOf("p0:$p0", "p1:$p1", "b:$p0>$candidate", "t:$p1>$p0>$candidate")
        contextTokens.forEachIndexed { idx, token ->
            if (token.length > 3) {
                val (bucket, sign) = hashBucket(token, 8)
                x[12 + bucket] += sign * (if (idx == 3) 1.5 else 1.0)
            }
        }
        // Dense typo and shape features.
        val common = typed.toSet().intersect(candidate.toSet()).size.toDouble()
        var prefix = 0
        while (prefix < minOf(typed.length, candidate.length) && typed[prefix] == candidate[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(typed.length, candidate.length) && typed[typed.length - 1 - suffix] == candidate[candidate.length - 1 - suffix]) suffix++
        x[20] = (distance / 4.0).coerceIn(0.0, 1.0)
        x[21] = (kotlin.math.abs(typed.length - candidate.length) / 5.0).coerceIn(0.0, 1.0)
        x[22] = prefix.toDouble() / max(1, max(typed.length, candidate.length))
        x[23] = suffix.toDouble() / max(1, max(typed.length, candidate.length))
        x[24] = (common / max(1, candidate.toSet().size)).coerceIn(0.0, 1.0)
        x[25] = (typed.length / 12.0).coerceIn(0.0, 1.0)
        x[26] = (candidate.length / 12.0).coerceIn(0.0, 1.0)
        x[27] = if (p0.isNotEmpty()) 1.0 else 0.0
        x[28] = if (p1.isNotEmpty()) 1.0 else 0.0
        x[29] = if (typed.firstOrNull() == candidate.firstOrNull()) 1.0 else 0.0
        x[30] = if (typed.lastOrNull() == candidate.lastOrNull()) 1.0 else 0.0
        x[31] = 1.0
        return x
    }

    private fun forward(m: Weights, x: DoubleArray): Pair<DoubleArray, Double> {
        val hidden = DoubleArray(HIDDEN) { h ->
            var z = m.b1[h]
            for (i in 0 until INPUTS) z += m.w1[h][i] * x[i]
            max(0.0, z)
        }
        var out = m.b2
        for (h in 0 until HIDDEN) out += m.w2[h] * hidden[h]
        return hidden to out.coerceIn(-8.0, 8.0)
    }

    fun score(prefs: SharedPreferences, typed: String, candidate: String, previous: List<String>, distance: Int): Double {
        val m = load(prefs)
        if (m.examples < 3) return 0.0 // avoid pretending an untrained model understands context
        return forward(m, features(typed, candidate, previous, distance)).second
    }

    /** Train pairwise: the explicitly selected word is positive; other close candidates are negative. */
    fun learnCorrection(
        prefs: SharedPreferences, typed: String, chosen: String, previous: List<String>,
        alternatives: List<PersianSpellChecker.Match>
    ) {
        if (typed.length !in 2..40 || chosen.length !in 2..40 || norm(typed) == norm(chosen)) return
        val m = load(prefs)
        val positives = features(typed, chosen, previous, editDistanceHint(typed, chosen))
        val negatives = alternatives.asSequence().map { it.word }.filter { norm(it) != norm(chosen) }
            .distinct().take(5).map { it to features(typed, it, previous, alternatives.firstOrNull { a -> norm(a.word) == norm(it) }?.distance ?: 3) }.toList()
        if (negatives.isEmpty()) return
        // SGD on pairwise logistic loss. Tiny network; training is local and fast.
        repeat(8) {
            for ((_, negative) in negatives) trainPair(m, positives, negative, 0.035)
        }
        m.examples = (m.examples + 1).coerceAtMost(MAX_EXAMPLES)
        save(prefs, m)
    }

    private fun trainPair(m: Weights, positive: DoubleArray, negative: DoubleArray, lr: Double) {
        val (hp, sp) = forward(m, positive)
        val (hn, sn) = forward(m, negative)
        val sigmoid = 1.0 / (1.0 + exp((sp - sn).coerceIn(-20.0, 20.0)))
        val gradP = -sigmoid
        val gradN = sigmoid
        val oldW2 = m.w2.copyOf()
        for (h in 0 until HIDDEN) {
            m.w2[h] -= lr * (gradP * hp[h] + gradN * hn[h])
            if (hp[h] > 0) {
                m.b1[h] -= lr * gradP * oldW2[h]
                for (i in 0 until INPUTS) m.w1[h][i] -= lr * gradP * oldW2[h] * positive[i]
            }
            if (hn[h] > 0) {
                m.b1[h] -= lr * gradN * oldW2[h]
                for (i in 0 until INPUTS) m.w1[h][i] -= lr * gradN * oldW2[h] * negative[i]
            }
        }
        m.b2 -= lr * (gradP + gradN)
    }

    private fun editDistanceHint(a: String, b: String): Int =
        kotlin.math.abs(a.length - b.length) + a.zip(b).count { it.first != it.second }

    fun rerank(
        prefs: SharedPreferences, typed: String, matches: List<PersianSpellChecker.Match>, previous: List<String>
    ): List<PersianSpellChecker.Match> {
        if (matches.size < 2 || prefs.getInt(EXAMPLES_KEY, 0) < 3) return matches
        return matches.sortedByDescending { m ->
            // Preserve lexical distance as the primary signal; neural score resolves contextual ties.
            -m.score + score(prefs, typed, m.word, previous, m.distance) * 180.0
        }
    }

    /** Neural-only auto correction is deliberately gated until enough explicit feedback exists. */
    fun confidentCorrection(
        prefs: SharedPreferences, typed: String, matches: List<PersianSpellChecker.Match>, previous: List<String>
    ): String? {
        if (trainedExamples(prefs) < 10 || matches.isEmpty()) return null
        val ordered = rerank(prefs, typed, matches, previous).take(2)
        val first = ordered.first()
        if (first.distance > 2) return null
        val firstScore = score(prefs, typed, first.word, previous, first.distance)
        val secondScore = ordered.getOrNull(1)?.let { score(prefs, typed, it.word, previous, it.distance) } ?: -10.0
        // Require a learned, clear contextual preference; otherwise show suggestions only.
        if (firstScore < 0.15 || firstScore - secondScore < 0.35) return null
        return first.word
    }

    fun trainedExamples(prefs: SharedPreferences): Int = prefs.getInt(EXAMPLES_KEY, 0)
}
