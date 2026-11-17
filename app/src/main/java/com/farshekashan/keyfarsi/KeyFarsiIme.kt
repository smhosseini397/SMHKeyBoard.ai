package com.farshekashan.keyfarsi

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class KeyFarsiIme : InputMethodService() {

    private var english = false
    private var numberMode = false
    private var clipboardMode = false
    private var symbolMode = false
    private var emojiMode = false
    private var uppercase = false
    private var emojiCategory = 0
    private var isLandscape = false

    private var suggestionRow: LinearLayout? = null
    private var keyboardRoot: LinearLayout? = null
    private data class PendingSend(val original: String, val corrected: String, val action: Int, val beforeLength: Int, val afterLength: Int)
    private var pendingSend: PendingSend? = null

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private val prefs by lazy { getSharedPreferences("keyfarsi", MODE_PRIVATE) }

    private val persianDictionary: List<String> by lazy {
        try { assets.open("fa_words.txt").bufferedReader(Charsets.UTF_8).useLines { it.filter { w -> w.isNotBlank() }.toList() } }
        catch (_: Exception) { emptyList() }
    }

    private val clipboard by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val DEFAULT_KEYBOARD_WIDTH = 378
    private val DEFAULT_KEYBOARD_HEIGHT = 349
    private val DEFAULT_KEY_WIDTH = 34
    private val DEFAULT_KEY_HEIGHT = 48
    private val DEFAULT_KEY_GAP = 5
    private val DEFAULT_PADDING = 2

    private fun t(key: String): String {
        return when (key) {
            "key_space" -> "فاصله"
            "key_shift" -> "⇧"
            "key_backspace" -> "⌫"
            "key_enter" -> "↵"
            "key_comma" -> ","
            "key_dot" -> "."
            "key_globe" -> "🌐"
            "mode_letters" -> "حروف"
            "mode_numbers" -> "اعداد"
            "mode_symbols" -> "نمادها"
            "mode_emoji" -> "ایموجی"
            "mode_back" -> "بازگشت"
            "mode_back_to_letters" -> "بازگشت به حروف"
            "clipboard_empty" -> "هنوز متنی ذخیره نشده است"
            "clipboard_save_current" -> "ذخیره متن فعلی"
            "clipboard_pin" -> "📌"
            "clipboard_unpin" -> "☆"
            "clipboard_delete" -> "×"
            "clipboard_pinned_section" -> "پین‌شده‌ها"
            "clipboard_recent_section" -> "۱۰ مورد آخر کپی‌شده"
            "clipboard_pin_first" -> "ابتدا پین را بردارید"
            "clipboard_recent_empty" -> "(خالی)"
            "toolbar_brand" -> "KeyFarsiSMH"
            "toolbar_numbers" -> "۱۲۳"
            "toolbar_symbols" -> "=\\<"
            "toolbar_emoji" -> "😊"
            "toolbar_clipboard" -> "📋"
            "toolbar_lang_fa" -> "فا"
            "toolbar_lang_en" -> "EN"
            "toolbar_settings" -> "⚙"
            else -> key
        }
    }

    private val persianRows = listOf(
        "ضصثقفغعهخحجچ",
        "شسیبلاتنمکگ",
        "ظطزرژذدپو"
    )

    private val englishRows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    private val alternateChars: Map<String, List<String>> = mapOf(
        "ا" to listOf("آ", "أ",  "ء", "ئ"),
        "ی" to listOf("ي", "ى", "ئ"),
        "ک" to listOf("گ"),
        "ه" to listOf("ة", "ۀ"),
        "و" to listOf("ؤ"),
        "ت" to listOf("ٹ"),
        "ر" to listOf("ژ"),
        "گ" to listOf("ګ"),
        "a" to listOf("à", "á", "â", "ä", "å", "æ", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"),
        "o" to listOf("ò", "ó", "ô", "ö", "ø", "ō"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń", "ň"),
        "s" to listOf("ś", "š", "ş"),
        "z" to listOf("ź", "ž", "ż"),
        "y" to listOf("ý", "ÿ")
    )

    private val allSymbols = listOf(
        "؟","،","؛",":","!","…",".",",","'","\"","(",")","[","]","{","}",
        "<",">","/","\\","|","_","-","+","=","*","%","@","#","$","^","~",
        "`","°","±","×","÷","√","≠","≈","≤","≥","∞","π","∑","∆","€","£",
        "¥","₹","¢","₽","₺","₿","&","§","©","®","™","✓","✔","✕","✖","★",
        "☆","•","●","○","٪","٫","٬","ـ","ٔ","‌","‍","ٓ","َ","ِ","ُ","ّ",
        "ْ","ً","ٍ","ٌ","«","»","‹","›","¿","¡","¶","†","‡","‰","‱","※",
        "℃","℉","←","→","↑","↓","↔","↕","⇐","⇒","⇑","⇓"
    )

    private val emojiCategories = listOf(
        "😀" to listOf("😀","😃","🙏","😁","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😗","😙","😚","😋","😛","😜","🤪","😎","🤓","🧐","🤔","🤗","🤭","🤫","🤥","😐","😑","😶","🙄","😏","😣","😥","😮","🤐","😯","😪","😫","🥱","😴","😷","🤒","🤕","🤢","🤮","🤧","🥵","🥶","🥴","😵","🤯","🤠","🥳","🥸","😈","👿","💀","👻"),
        "❤️" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟","♥️","💌","💋","💍","💎"),
        "👋" to listOf("👋","🤚","🖐️","✋","🖖","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","👐","🤲","🤝","🙏","✍️","💅","💪","🦾","🦿","🦵","🦶","👂","👃","🧠","🫀","🫁","🦷","🦴","👀","👁️"),
        "🐱" to listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐻‍❄️","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔","🐧","🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄","🐝","🪱","🐛","🦋","🐌","🐞","🐜","🪰","🕷️","🦂","🐢","🐍","🦎","🦖","🦕","🐙","🦑","🦐","🦀","🐠"),
        "🍎" to listOf("🍎","🍏","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑","🥦","🥬","🥒","🌶️","🫑","🌽","🥕","🧄","🧅","🥔","🍠","🥐","🥯","🍞","🥖","🥨","🧀","🥚","🍳","🧈","🥞","🧇","🥓","🥩","🍗","🍖","🌭","🍔","🍟"),
        "⚽" to listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍","🏏","🪃","🥅","⛳","🪁","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🥌","🎿","⛷️","🏂","🏋️","🤼","🤸","⛹️","🤺"),
        "🚗" to listOf("🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🛻","🚚","🚛","🚜","🛵","🏍️","🚲","🛴","🚏","🛣️","🛤️","⛽","🚨","🚥","🚦","🚧","⚓","⛵","🚤","🛳️","✈️","🚀","🛸","🚁","🚂","🚆","🚇","🚊","🚉","🚄"),
        "💡" to listOf("💡","📱","💻","⌨️","🖥️","🖨️","🖱️","🖲️","💾","💿","📀","📷","📸","📹","🎥","📞","☎️","📺","📻","🎙️","🎚️","🎛️","🧭","⏱️","⏰","⌚","🔋","🪫","🔌","💡","🔦","🕯️","🧯","🛠️","🔧","🔨","⚙️","🧰","🪛","🔩"),
        "🌸" to listOf("🌸","🌹","🌺","🌻","🌼","🌷","🪻","💐","🌱","🌿","☘️","🍀","🌵","🌴","🌳","🌲","🌾","🌞","🌝","🌛","🌜","🌚","🌕","🌙","⭐","🌟","✨","⚡","🔥","💧","🌊","☀️","🌈","☁️","⛅","🌤️","🌧️","⛈️","❄️","☃️"),
        "🎉" to listOf("🎉","🎊","🎈","🎁","🎀","🎂","🍰","🧁","🎄","🎃","🎆","🎇","🧨","✨","🎐","🎏","🎎","🎍","🎋","🎑","🧧","🎗️","🎟️","🎫","🏆","🥇","🥈","🥉","🏅","🎖️","🎯","🎮","🕹️","🎲","♟️","🧩","🪅","🪩","🎨","🖼️")
    )

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { saveClipboardCurrent() }

    override fun onCreate() {
        super.onCreate()
        clipboard.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        stopRepeat()
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        pendingSend = null
        saveClipboardCurrent()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        saveClipboardCurrent()
    }

    override fun onCreateInputView(): View {
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return buildKeyboard()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!isInputViewShown) return
        val navBarHeight = getNavigationBarHeight()
        val keyboardHeight = keyboardRoot?.height ?: 0
        outInsets.contentTopInsets = outInsets.visibleTopInsets
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, 0, resources.displayMetrics.widthPixels, (keyboardHeight - navBarHeight).coerceAtLeast(0))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isInputViewShown) setInputView(buildKeyboard())
    }

    private fun getNavigationBarHeight(): Int {
        val res = resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    private fun buildKeyboard(): View {
        val dark = prefs.getBoolean("dark", false)
        val theme = prefs.getString("theme", "default")
        
        var background = if (dark) Color.rgb(29, 29, 35) else Color.rgb(232, 228, 239)
        var normalKey = if (dark) Color.rgb(57, 55, 66) else Color.WHITE
        var foreground = if (dark) Color.WHITE else Color.rgb(38, 34, 47)
        var accent = Color.rgb(103, 80, 164)

        if (theme == "retro") {
            background = Color.rgb(50, 50, 50); normalKey = Color.rgb(100, 100, 100); foreground = Color.WHITE; accent = Color.rgb(200, 100, 50)
        } else if (theme == "neon") {
            background = Color.rgb(0, 0, 0); normalKey = Color.rgb(30, 30, 30); foreground = Color.rgb(0, 255, 0); accent = Color.rgb(255, 0, 255)
        } else if (theme == "glass") {
            background = Color.rgb(40, 40, 60); normalKey = Color.rgb(100, 100, 150); foreground = Color.WHITE; accent = Color.rgb(0, 200, 255)
        }

        val requestedWidth = prefs.getInt("keyboard_width", 0)
        val requestedWidthLandscape = prefs.getInt("keyboard_width_landscape", 0)
        val requestedHeight = prefs.getInt("keyboard_height", 0)
        val requestedHeightLandscape = prefs.getInt("keyboard_height_landscape", 0)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val paddingPx = dp(prefs.getInt("keyboard_padding", DEFAULT_PADDING).coerceIn(1, 5))

        val actualWidth = if (isLandscape) {
            if (requestedWidthLandscape > 0) dp(requestedWidthLandscape).coerceAtMost(screenWidth) - (paddingPx * 2) else screenWidth - (paddingPx * 2)
        } else {
            if (requestedWidth > 0) dp(requestedWidth).coerceAtMost(screenWidth) - (paddingPx * 2) else screenWidth - (paddingPx * 2)
        }

        val heightToUse = if (isLandscape) {
            if (requestedHeightLandscape > 0) dp(requestedHeightLandscape) else (screenHeight * 0.45).toInt()
        } else {
            dp(if (requestedHeight > 0) requestedHeight else DEFAULT_KEYBOARD_HEIGHT)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(background)
            val navBar = getNavigationBarHeight()
            setPadding(paddingPx, paddingPx, paddingPx, navBar + paddingPx)
            layoutParams = ViewGroup.LayoutParams(screenWidth, heightToUse)
        }
        keyboardRoot = root

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(actualWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(content)

        buildToolbar(content, normalKey, foreground, accent)

        when {
            clipboardMode -> buildClipboard(content, normalKey, foreground)
            emojiMode -> buildEmojiPage(content, normalKey, foreground)
            symbolMode -> buildSymbolPage(content, normalKey, foreground)
            numberMode -> buildNumberPad(content, normalKey, foreground)
            else -> {
                buildSuggestions(content, normalKey, foreground)
                buildLetterKeyboard(content, normalKey, foreground, accent, actualWidth)
            }
        }
        return root
    }

    private fun buildToolbar(root: LinearLayout, keyBg: Int, fg: Int, accent: Int) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(label(t("toolbar_brand"), 11f, fg).apply { 
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(34), 0.8f))
        
        bar.addView(actionButton(t("toolbar_settings"), keyBg, fg) {
            startActivity(Intent(this@KeyFarsiIme, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        })
        bar.addView(actionButton(t("toolbar_numbers"), keyBg, fg) { toggleMode { numberMode = !numberMode } })
        bar.addView(actionButton(t("toolbar_symbols"), keyBg, fg) { toggleMode { symbolMode = !symbolMode } })
        bar.addView(actionButton(t("toolbar_clipboard"), keyBg, fg) {
            numberMode = false; symbolMode = false; emojiMode = false; clipboardMode = true; refreshKeyboard()
        })
        bar.addView(actionButton(if (english) t("toolbar_lang_fa") else t("toolbar_lang_en"), accent, Color.WHITE) {
            english = !english; uppercase = false; refreshKeyboard()
        })
        root.addView(bar)
    }

    private inline fun toggleMode(block: () -> Unit) {
        numberMode = false; symbolMode = false; emojiMode = false; clipboardMode = false
        block()
        refreshKeyboard()
    }

    private fun buildSuggestions(root: LinearLayout, keyBg: Int, fg: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(38)
        }
        suggestionRow = row
        root.addView(row)
        refreshSuggestions(keyBg, fg)
    }

    private fun refreshSuggestions(keyBg: Int? = null, fg: Int? = null) {
        val row = suggestionRow ?: return
        row.removeAllViews()
        val dark = prefs.getBoolean("dark", false)
        val buttonBg = keyBg ?: if (dark) Color.rgb(57, 55, 66) else Color.WHITE
        val textColor = fg ?: if (dark) Color.WHITE else Color.rgb(38, 34, 47)

        val currentWord = getCurrentWord()
        val suggestions = mutableListOf<String>()

        val lastWord = getLastCompletedWord()
        if (lastWord.isNotEmpty()) {
            val bigrams = loadBigrams()
            val nextWords = bigrams.optJSONObject(lastWord)
            if (nextWords != null) {
                val keys = nextWords.keys()
                val list = mutableListOf<Pair<String, Int>>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    list.add(k to nextWords.optInt(k, 0))
                }
                suggestions.addAll(list.sortedByDescending { it.second }.map { it.first }.take(3))
            }
        }

        val autoCorrect = prefs.getBoolean("auto_correct", true)
        if (currentWord.isNotBlank() && !isSensitiveInput() && !english) {
            val typoMatches = if (autoCorrect) {
                val remembered = rememberedCorrection(currentWord)
                if (remembered != null) listOf(remembered) else
                    rankedSpellMatches(currentWord, getLastCompletedWords()).take(3).map { m -> m.word }
            } else {
                findSuggestions(currentWord).take(3)
            }
            suggestions.addAll(0, typoMatches.filterNot { it in suggestions })
        }
        if (suggestions.size < 3 && currentWord.isNotBlank()) {
            val completions = findSuggestions(currentWord).take(3 - suggestions.size)
            suggestions.addAll(completions)
        }

        val visibleSuggestions = suggestions.distinct().take(3).toMutableList()
        while (visibleSuggestions.size < 3) visibleSuggestions.add(" ")

        visibleSuggestions.forEach { word ->
            val button = TextView(this).apply {
                text = word
                textSize = 14f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(5), dp(3), dp(5), dp(3))
                background = createKeyBackground(buttonBg)
                isClickable = true
                setOnClickListener {
                    if (word.isNotBlank()) replaceCurrentWord(word)
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        }
    }

    private fun getLastCompletedWord(): String {
        return try {
            val before = currentInputConnection?.getTextBeforeCursor(100, 0)?.toString().orEmpty()
            val match = Regex("([\\p{L}\\p{N}]+)[\\s.,!?؛]+$").find(before)
            match?.groupValues?.get(1) ?: ""
        } catch (_: Exception) { "" }
    }

    private fun getLastCompletedWords(): List<String> {
        return try {
            val before = currentInputConnection?.getTextBeforeCursor(180, 0)?.toString().orEmpty()
            val current = getCurrentWord()
            val completedText = if (current.isNotEmpty() && before.endsWith(current)) before.dropLast(current.length) else before
            Regex("[\\p{L}\\p{M}\\u200c\\p{N}]+(?=[\\s.,!?؛،:]+|$)")
                .findAll(completedText.trimEnd()).map { it.value }.toList().takeLast(2).reversed()
        } catch (_: Exception) { emptyList() }
    }

    private fun saveTrigram(firstWord: String, secondWord: String, currentWord: String) {
        if (firstWord.isBlank() || secondWord.isBlank() || currentWord.isBlank()) return
        try {
            val trigrams = loadTrigrams()
            val contextKey = "${PersianSpellChecker.normalize(firstWord)}\u001f${PersianSpellChecker.normalize(secondWord)}"
            val nextWords = trigrams.optJSONObject(contextKey) ?: JSONObject()
            val normalizedNext = PersianSpellChecker.normalize(currentWord)
            nextWords.put(normalizedNext, (nextWords.optInt(normalizedNext, 0) + 1).coerceAtMost(100))
            trigrams.put(contextKey, nextWords)
            if (trigrams.length() > 1200) {
                val keys = trigrams.keys()
                val trimmed = JSONObject()
                var kept = 0
                while (keys.hasNext() && kept < 1000) { val key = keys.next(); trimmed.put(key, trigrams.optJSONObject(key)); kept++ }
                prefs.edit().putString("trigrams", trimmed.toString()).apply()
            } else prefs.edit().putString("trigrams", trigrams.toString()).apply()
        } catch (e: Exception) { android.util.Log.e("KeyFarsi", "trigram save error", e) }
    }

    private fun loadTrigrams(): JSONObject {
        return try { JSONObject(prefs.getString("trigrams", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    }

    private fun saveBigram(prevWord: String, currentWord: String) {
        if (prevWord.isBlank() || currentWord.isBlank()) return
        if (prevWord.length < 1 || currentWord.length < 1) return
        try {
            val bigrams = loadBigrams()
            val nextWords = bigrams.optJSONObject(prevWord) ?: JSONObject()
            val count = nextWords.optInt(currentWord, 0)
            nextWords.put(currentWord, (count + 1).coerceAtMost(100))
            bigrams.put(prevWord, nextWords)
            prefs.edit().putString("bigrams", bigrams.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("KeyFarsi", "bigram save error", e)
        }
    }

    private fun loadBigrams(): JSONObject {
        return try { JSONObject(prefs.getString("bigrams", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    }

    private fun buildLetterKeyboard(root: LinearLayout, keyBg: Int, fg: Int, accent: Int, actualWidth: Int) {
        val standardBig = prefs.getBoolean("standard_big_keyboard", false)
        val rows = if (english) englishRows else persianRows

        val usableWidthPx = actualWidth - dp(8)

        val prefix = if (english) "en_" else "fa_"
        val rawKeyWidth = prefs.getInt("${prefix}key_width", 0)
        val rawKeyHeight = prefs.getInt("${prefix}key_height", 0)
        val rawGap = prefs.getInt("${prefix}key_gap", 0)

        val configuredKeyWidth = if (rawKeyWidth > 0) rawKeyWidth else DEFAULT_KEY_WIDTH
        val configuredKeyHeight = if (rawKeyHeight > 0) rawKeyHeight else DEFAULT_KEY_HEIGHT
        val configuredGap = if (rawGap > 0) rawGap else DEFAULT_KEY_GAP

        if (standardBig) {
            val stdKeyWidth = prefs.getInt("standard_key_width", 0)
            val stdKeyHeight = prefs.getInt("standard_key_height", 0)
            val stdGap = prefs.getInt("standard_key_gap", -1)
            val scale = prefs.getInt("standard_scale", 100).coerceIn(50, 150) / 100f
            val gapPx = if (stdGap >= 0) dp(stdGap) else dp(configuredGap)
            val removedKeys = if (!english) {
                val removed = mutableSetOf<Char>()
                if (prefs.getBoolean("merge_key_kaf", false)) removed.add('ک')
                if (prefs.getBoolean("merge_key_gaf", false)) removed.add('گ')
                if (prefs.getBoolean("merge_key_re", false)) removed.add('ر')
                if (prefs.getBoolean("merge_key_zhe", false)) removed.add('ژ')
                removed
            } else emptySet()
            val allChars = mutableListOf<Char>()
            rows.forEach { row -> row.forEach { ch -> if (ch !in removedKeys) allChars.add(ch) } }
            val columns = (allChars.size / 3).coerceAtLeast(6)
            val chunks = allChars.chunked(columns)
            val maxAvailableWidth = usableWidthPx - (columns * gapPx * 2)
            val calculatedKeyWidth = if (stdKeyWidth > 0) dp(stdKeyWidth) else maxAvailableWidth / columns
            val keyWidthPx = (calculatedKeyWidth * scale).toInt().coerceIn(dp(20), maxAvailableWidth / columns)
            val baseKeyHeight = if (stdKeyHeight > 0) dp(stdKeyHeight) else if (isLandscape) dp(40) else dp(56)
            val keyHeightPx = (baseKeyHeight * scale).toInt().coerceAtLeast(dp(24))
            val spaceWeight = prefs.getInt("space_weight", 3).coerceIn(1, 8).toFloat()
            val enterWeight = prefs.getInt("enter_weight", 15).coerceIn(10, 25) / 10f
            chunks.forEachIndexed { index, chunk ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                val emptyCount = columns - chunk.size
                val emptyLeft = emptyCount / 2
                val emptyRight = emptyCount - emptyLeft
                if (index == chunks.lastIndex) {
                    if (english) {
                        line.addView(actionButton(t("key_shift"), Color.rgb(190, 155, 215), Color.WHITE) {
                            uppercase = !uppercase; refreshKeyboard()
                        }, LinearLayout.LayoutParams((keyWidthPx * 1.5).toInt(), keyHeightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
                    }
                    repeat(emptyLeft) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeightPx, gapPx)) }
                    chunk.forEach { item ->
                        val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                        line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeightPx, gapPx) { commit(v) })
                    }
                    repeat(emptyRight) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeightPx, gapPx)) }
                    line.addView(backspaceKeySized(Color.rgb(190, 155, 215), Color.WHITE, (keyWidthPx * 1.5).toInt(), keyHeightPx, gapPx))
                } else {
                    repeat(emptyLeft) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeightPx, gapPx)) }
                    chunk.forEach { item ->
                        val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                        line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeightPx, gapPx) { commit(v) })
                    }
                    repeat(emptyRight) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeightPx, gapPx)) }
                }
                root.addView(line)
            }
            val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            bottom.addView(actionButton("?123", keyBg, fg) { toggleMode { numberMode = true } }, LinearLayout.LayoutParams(0, keyHeightPx, 1.5f).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
            bottom.addView(actionButton(t("key_globe"), keyBg, fg) { english = !english; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeightPx, 1f).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
            if (english) bottom.addView(actionButton(t("key_shift"), keyBg, fg) { uppercase = !uppercase; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeightPx, 1f).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
            bottom.addView(keyButton(t("key_space"), keyBg, fg) { commit(" ") }, LinearLayout.LayoutParams(0, keyHeightPx, spaceWeight).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
            bottom.addView(actionButton(t("key_enter"), accent, Color.WHITE) { sendEnter() }, LinearLayout.LayoutParams(0, keyHeightPx, enterWeight).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
            root.addView(bottom)
        } else {
            val keyWidthPx = dp(configuredKeyWidth)
            val gapPx = dp(configuredGap)
            val effectiveKeyHeight = dp(configuredKeyHeight)

            val maxColumns = ((usableWidthPx + gapPx) / (keyWidthPx + gapPx * 2)).coerceAtLeast(1)
            
            val allChars = mutableListOf<Char>()
            rows.forEach { row -> row.forEach { ch -> allChars.add(ch) } }
            val chunks = allChars.chunked(maxColumns)

            chunks.forEachIndexed { index, chunk ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                val emptyCount = maxColumns - chunk.size
                val emptyLeft = emptyCount / 2
                val emptyRight = emptyCount - emptyLeft

                if (index == chunks.lastIndex) {
                    repeat(emptyLeft) { line.addView(emptyFixedKeySized(keyWidthPx, effectiveKeyHeight, gapPx)) }
                    chunk.forEach { item ->
                        val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                        line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, effectiveKeyHeight, gapPx) { commit(v) })
                    }
                    line.addView(backspaceKeySized(Color.rgb(190, 155, 215), Color.WHITE, keyWidthPx * 2, effectiveKeyHeight, gapPx))
                } else {
                    repeat(emptyLeft) { line.addView(emptyFixedKeySized(keyWidthPx, effectiveKeyHeight, gapPx)) }
                    chunk.forEach { item ->
                        val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                        line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, effectiveKeyHeight, gapPx) { commit(v) })
                    }
                    repeat(emptyRight) { line.addView(emptyFixedKeySized(keyWidthPx, effectiveKeyHeight, gapPx)) }
                }
                root.addView(line)
            }

            buildBottomRow(root, keyBg, fg)
        }
    }

       private fun addStandardRow(root: LinearLayout, rowStr: String, columns: Int, keyWidthPx: Int, keyHeightPx: Int, gapPx: Int, keyBg: Int, fg: Int) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val chars = rowStr.toList()
        val emptyCount = columns - chars.size
        val emptyLeft = emptyCount / 2
        val emptyRight = emptyCount - emptyLeft
        repeat(emptyLeft) { line.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(keyWidthPx, keyHeightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) } }) }
        chars.forEach { item ->
            val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
            line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeightPx, gapPx) { commit(v) })
        }
        repeat(emptyRight) { line.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(keyWidthPx, keyHeightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) } }) }
        root.addView(line)
    }

    private fun letterKeySized(text: String, bg: Int, fg: Int, widthPx: Int, heightPx: Int, gapPx: Int, click: () -> Unit): TextView {
        val key = keyButton(text, bg, fg) { click() }
        key.layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) }
        return key
    }

    private fun emptyFixedKeySized(widthPx: Int, heightPx: Int, gapPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) }
            alpha = 0f
        }
    }

    private fun backspaceKeySized(bg: Int, fg: Int, widthPx: Int, heightPx: Int, gapPx: Int): TextView {
        val key = TextView(this).apply {
            text = t("key_backspace")
            textSize = 14f
            setTextColor(fg)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(2), dp(2), dp(2), dp(5))
            background = createKeyBackground(bg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(3).toFloat()
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) }
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { view.animate().translationY(dp(2).toFloat()).setDuration(40).start(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dp(1).toFloat(); deleteOneChar(); startRepeat(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.animate().translationY(0f).setDuration(60).start(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.animate().setDuration(60).withEndAction { view.elevation = dp(3).toFloat() }.start() }; stopRepeat(); true }
                    else -> false
                }
            }
        }
        return key
    }

    private fun startRepeat() {
        stopRepeat()
        repeatRunnable = Runnable { deleteOneChar(); handler.postDelayed(repeatRunnable!!, 50L) }
        handler.postDelayed(repeatRunnable!!, 400L)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun deleteOneChar() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
        refreshSuggestions()
    }

    private fun buildBottomRow(root: LinearLayout, keyBg: Int, fg: Int) {
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val spaceWeight = prefs.getInt("space_weight", 3).coerceIn(1, 8).toFloat()
        val enterWeight = prefs.getInt("enter_weight", 15).coerceIn(10, 25) / 10f
        bottom.addView(actionButton("?123", keyBg, fg) { toggleMode { numberMode = true } }, LinearLayout.LayoutParams(0, keyHeight(), 1.5f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        bottom.addView(actionButton(t("key_globe"), keyBg, fg) { english = !english; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeight(), 1f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        if (english) bottom.addView(actionButton(t("key_shift"), keyBg, fg) { uppercase = !uppercase; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeight(), 1f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        bottom.addView(keyButton(t("key_space"), keyBg, fg) { commit(" ") }, LinearLayout.LayoutParams(0, keyHeight(), spaceWeight).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        bottom.addView(actionButton(t("key_enter"), Color.rgb(103, 80, 164), Color.WHITE) { sendEnter() }, LinearLayout.LayoutParams(0, keyHeight(), enterWeight).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        root.addView(bottom)
    }
    private fun buildNumberPad(root: LinearLayout, keyBg: Int, fg: Int) {
        val numKeyHeight = if (isLandscape) dp(32) else keyHeight()

        val rows = listOf(
            listOf("۱", "۲", "۳"),
            listOf("۴", "۵", "۶"),
            listOf("۷", "۸", "۹"),
            listOf("⌫", "۰")
        )
        rows.forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { value ->
                if (value == "⌫") {
                    line.addView(backspaceKeySized(keyBg, fg, (resources.displayMetrics.widthPixels - dp(8)) / 3, numKeyHeight, dp(gap())))
                } else {
                    val display = if (english) toEnglishDigits(value) else value
                    line.addView(keyButton(display, keyBg, fg, 20f) { commit(if (english) toEnglishDigits(value) else value) }, LinearLayout.LayoutParams(0, numKeyHeight, 1f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
                }
            }
            root.addView(line)
        }

        val symbolsRow = listOf("؟","%","@","×","*","(",")","!","+","_","=",".")
        val symLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        symbolsRow.forEach { s ->
            symLine.addView(keyButton(s, keyBg, fg, 16f) { commit(s) }, LinearLayout.LayoutParams(0, numKeyHeight, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        root.addView(symLine)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(actionButton(t("mode_letters"), keyBg, fg) { numberMode = false; refreshKeyboard() }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        controls.addView(actionButton(t("mode_symbols"), keyBg, fg) { symbolMode = true; numberMode = false; refreshKeyboard() }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        controls.addView(actionButton(t("mode_emoji"), keyBg, fg) { emojiMode = true; numberMode = false; refreshKeyboard() }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        controls.addView(keyButton(" ", keyBg, fg) { commit(" ") }, LinearLayout.LayoutParams(0, numKeyHeight, 2f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        controls.addView(actionButton(t("key_enter"), Color.rgb(103, 80, 164), Color.WHITE) { sendEnter() }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        root.addView(controls)
    }

    private fun buildSymbolPage(root: LinearLayout, keyBg: Int, fg: Int) {
        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        allSymbols.chunked(8).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { symbol ->
                line.addView(keyButton(symbol, keyBg, fg, 18f) { commit(symbol) }, LinearLayout.LayoutParams(0, keyHeight(), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            column.addView(line)
        }
        scroller.addView(column)
        val symbolHeight = if (isLandscape) (resources.displayMetrics.heightPixels * 0.5).toInt() else dp(260)
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, symbolHeight))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(actionButton(t("mode_numbers"), keyBg, fg) { symbolMode = false; numberMode = true; refreshKeyboard() })
        controls.addView(actionButton(t("mode_emoji"), keyBg, fg) { symbolMode = false; emojiMode = true; refreshKeyboard() })
        controls.addView(actionButton(t("mode_letters"), Color.rgb(103, 80, 164), Color.WHITE) { symbolMode = false; refreshKeyboard() })
        root.addView(controls)
    }

    private fun buildEmojiPage(root: LinearLayout, keyBg: Int, fg: Int) {
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        emojiCategories.forEachIndexed { index, category ->
            categories.addView(actionButton(category.first, keyBg, fg) { emojiCategory = index; refreshKeyboard() })
        }
                val categoryScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(categories) }
        root.addView(categoryScroll)

        val emojis = emojiCategories[emojiCategory].second
        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emojis.chunked(8).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { emoji ->
                line.addView(keyButton(emoji, keyBg, fg, 22f) { commit(emoji) }, LinearLayout.LayoutParams(0, keyHeight(), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            column.addView(line)
        }
        scroller.addView(column)
        val emojiHeight = if (isLandscape) (resources.displayMetrics.heightPixels * 0.5).toInt() else dp(260)
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, emojiHeight))

        root.addView(actionButton(t("mode_back_to_letters"), Color.rgb(103, 80, 164), Color.WHITE) { emojiMode = false; refreshKeyboard() })
    }

    private fun buildClipboard(root: LinearLayout, keyBg: Int, fg: Int) {
        val list = loadClipboard()
        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pinnedItems = mutableListOf<Pair<Int, JSONObject>>()
        val recentItems = mutableListOf<Pair<Int, JSONObject>>()

        for (i in list.length() - 1 downTo 0) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optBoolean("pinned")) pinnedItems.add(i to item) else recentItems.add(i to item)
        }

        column.addView(label("🕐 ${t("clipboard_recent_section")}", 13f, fg).apply { setPadding(dp(6), dp(8), dp(6), dp(4)); setTypeface(null, Typeface.BOLD) })
        val recent = recentItems.take(10)
        if (recent.isEmpty()) column.addView(label(t("clipboard_recent_empty"), 12f, fg).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) })
        else recent.forEach { (index, item) -> column.addView(buildClipboardRow(list, index, item, keyBg, fg)) }

        if (pinnedItems.isNotEmpty()) {
            column.addView(label("📌 ${t("clipboard_pinned_section")}", 13f, fg).apply { setPadding(dp(6), dp(8), dp(6), dp(4)); setTypeface(null, Typeface.BOLD) })
            pinnedItems.forEach { (index, item) -> column.addView(buildClipboardRow(list, index, item, keyBg, fg)) }
        }

        if (pinnedItems.isEmpty() && recentItems.isEmpty()) {
            column.addView(label(t("clipboard_empty"), 15f, fg).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) })
        }

        scroller.addView(column)
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton(t("clipboard_save_current"), keyBg, fg) { saveClipboardCurrent(); refreshKeyboard() })
        actions.addView(actionButton(t("mode_back"), Color.rgb(103, 80, 164), Color.WHITE) { clipboardMode = false; refreshKeyboard() })
        root.addView(actions)
    }

    private fun buildClipboardRow(list: JSONArray, index: Int, item: JSONObject, keyBg: Int, fg: Int): LinearLayout {
        val text = item.optString("text")
        val pinned = item.optBoolean("pinned")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(keyButton((if (pinned) "📌 " else "") + text.take(40), keyBg, fg, 13f) { commit(text) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) })
        row.addView(actionButton(if (pinned) t("clipboard_pin") else t("clipboard_unpin"), keyBg, fg) { item.put("pinned", !pinned); saveClipboard(list); refreshKeyboard() })
        row.addView(actionButton(t("clipboard_delete"), keyBg, fg) {
            for (i in list.length() - 1 downTo 0) { if (list.optJSONObject(i)?.optString("text") == text) { list.remove(i); break } }
            saveClipboard(list); refreshKeyboard()
        })
        return row
    }

    private fun keyHeight(): Int {
        val configured = prefs.getInt("key_height", 0)
        return dp(if (configured <= 0) DEFAULT_KEY_HEIGHT else configured)
    }

    private fun gap(): Int {
        val configured = prefs.getInt("key_gap", 0)
        return if (configured <= 0) DEFAULT_KEY_GAP else configured
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun keyButton(text: String, bg: Int, fg: Int, size: Float = 18f, click: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(fg)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            maxLines = 1
            setPadding(dp(2), dp(2), dp(2), dp(5))
            background = createKeyBackground(bg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = dp(3).toFloat(); setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { view.animate().translationY(dp(2).toFloat()).setDuration(40).start(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dp(1).toFloat() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.animate().translationY(0f).setDuration(60).start(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.animate().setDuration(60).withEndAction { view.elevation = dp(3).toFloat() }.start() } }
                }
                false
            }
            setOnClickListener { click() }
            var alternates = alternateChars[text.lowercase()] ?: alternateChars[text]
if (text == "ر" && prefs.getBoolean("merge_re_zhe", false)) alternates = listOf("ژ")
if (text == "ک" && prefs.getBoolean("merge_kaf_gaf", false)) alternates = listOf("گ")
            if (!alternates.isNullOrEmpty()) setOnLongClickListener { v -> showAlternativesPopup(v, alternates); true }
        }
    }

    private fun showAlternativesPopup(anchor: View, alternatives: List<String>) {
        val popup = PopupWindow(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4)); setBackgroundColor(Color.rgb(250, 250, 252)) }
        alternatives.forEach { alt ->
            val btn = TextView(this).apply {
                text = alt; textSize = 20f; setTextColor(Color.rgb(38, 34, 47)); gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8)); background = createKeyBackground(Color.WHITE); isClickable = true
                setOnClickListener { commit(alt); popup.dismiss() }
            }
            container.addView(btn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        popup.contentView = container
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.elevation = dp(6).toFloat()
        popup.showAsDropDown(anchor, 0, -anchor.height - dp(60))
    }

    private fun actionButton(text: String, bg: Int, fg: Int, click: () -> Unit): TextView {
        return keyButton(text, bg, fg, 14f, click).apply {
            minimumWidth = dp(40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, keyHeight()).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        }
    }

    private fun createKeyBackground(color: Int): LayerDrawable {
        val dark = prefs.getBoolean("dark", false)
        val topColor = if (dark) lighten(color, 18) else lighten(color, 8)
        val bottomColor = if (dark) darken(color, 18) else darken(color, 12)
        val main = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topColor, bottomColor)).apply {
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), if (dark) 0xFF55515F.toInt() else 0xFFE0DCE7.toInt())
        }
        val shadow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(darken(bottomColor, 30), darken(bottomColor, 40))).apply { cornerRadius = dp(9).toFloat() }
        return LayerDrawable(arrayOf(InsetDrawable(shadow, 0, 0, 0, dp(2)), main))
    }

    private fun lighten(color: Int, amount: Int): Int {
        val r = min(255, Color.red(color) + amount); val g = min(255, Color.green(color) + amount); val b = min(255, Color.blue(color) + amount)
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, amount: Int): Int {
        val r = max(0, Color.red(color) - amount); val g = max(0, Color.green(color) - amount); val b = max(0, Color.blue(color) - amount)
        return Color.rgb(r, g, b)
    }

    private fun refreshKeyboard() {
        suggestionRow = null
        stopRepeat()
        setInputView(buildKeyboard())
    }

    private fun commit(value: String) {
        val currentWord = getCurrentWord()
        val priorWords = getLastCompletedWords()
        val lastWord = priorWords.firstOrNull().orEmpty()
        val beforeCursor = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()

        var correctedCurrentWord = currentWord
        val autoCorrect = prefs.getBoolean("auto_correct", true)
        if (autoCorrect && value == " " && currentWord.length >= 2 && !english && !isSensitiveInput()) {
            val ranked = rankedSpellMatches(currentWord, priorWords)
            val beforeWord = currentInputConnection?.getTextBeforeCursor(240, 0)?.toString().orEmpty()
            val semanticPrefix = beforeWord.removeSuffix(currentWord).trim()
            val correction = rememberedCorrection(currentWord)
                ?: PersianAlBertSemanticModel.confidentCorrection(this, semanticPrefix, ranked)
                ?: PersianNeuralContextModel.confidentCorrection(prefs, currentWord, ranked, priorWords)
                ?: PersianSpellChecker.confidentCorrection(currentWord, spellCandidates(priorWords), ranked)
            if (correction != null && PersianSpellChecker.normalize(correction) != PersianSpellChecker.normalize(currentWord)) {
                val ic = currentInputConnection
                ic?.deleteSurroundingText(currentWord.length, 0)
                ic?.commitText(correction, 1)
                correctedCurrentWord = correction
                learnWord(correction)
            }
        }
        if (value == " " || value == "\n" || value in listOf(".", ",", "!", "؟", "؛")) {
            if (lastWord.isNotBlank() && correctedCurrentWord.isNotBlank()) {
                saveBigram(lastWord, correctedCurrentWord)
                if (priorWords.size >= 2) saveTrigram(priorWords[1], priorWords[0], correctedCurrentWord)
            }
            learnWord(correctedCurrentWord)
        }

        if (beforeCursor.isNotEmpty() && beforeCursor.last().isDigit() && value.firstOrNull()?.isLetter() == true) {
            currentInputConnection?.commitText(" ", 1)
        }

        val finalValue = if (isNumericInput()) toEnglishDigits(value) else value
        currentInputConnection?.commitText(finalValue, 1)
        if (value == " ") uppercase = false
        refreshSuggestions()
    }

    private fun isNumericInput(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        val inputClass = type and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
    }

    private fun sendEnter() {
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val inputType = info.inputType and InputType.TYPE_MASK_CLASS
        val isMultiline = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (isMultiline && inputType == InputType.TYPE_CLASS_TEXT) {
            ic.commitText("\n", 1)
            return
        }

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        when (action) {
            EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO -> ic.performEditorAction(action)
            EditorInfo.IME_ACTION_SEND -> reviewBeforeSend(action)
            else -> ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
        }
    }

    private fun reviewBeforeSend(action: Int) {
        val ic = currentInputConnection ?: return
        if (isSensitiveInput()) {
            ic.performEditorAction(action)
            return
        }

        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.performEditorAction(action)
            return
        }

        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()
        val original = before + after
        if (original.isBlank() || original.length > 4000) {
            ic.performEditorAction(action)
            return
        }

        val corrected = correctWholePersianText(original)
        if (corrected == original) {
            ic.performEditorAction(action)
            return
        }

        pendingSend = PendingSend(original, corrected, action, before.length, after.length)
        setInputView(buildSendReviewView(original, corrected))
    }

    private fun correctWholePersianText(text: String): String {
        if (english || isSensitiveInput() || text.isBlank()) return text
        val tokenRegex = Regex("[\\p{L}\\p{M}\\u200c]+")
        val matches = tokenRegex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val replacements = ArrayList<Pair<IntRange, String>>()
        val previousWords = ArrayList<String>()
        for (match in matches) {
            val word = match.value
            val isPersian = word.any { it in '\u0600'..'\u06FF' }
            var replacement: String? = null
            if (isPersian && word.length >= 3) {
                replacement = rememberedCorrection(word)
                if (replacement == null) {
                    val ranked = rankedSpellMatches(word, previousWords.takeLast(2).reversed())
                    replacement = PersianSpellChecker.confidentCorrection(word, emptySequence(), ranked)
                }
            }
            if (!replacement.isNullOrBlank() && PersianSpellChecker.normalize(replacement) != PersianSpellChecker.normalize(word)) {
                replacements += match.range to replacement
            }
            previousWords += word
        }
        if (replacements.isEmpty()) return text
        return buildString(text.length + 16) {
            var cursor = 0
            for ((range, replacement) in replacements) {
                append(text, cursor, range.first)
                append(replacement)
                cursor = range.last + 1
            }
            append(text, cursor, text.length)
        }
    }
        private fun buildSendReviewView(original: String, corrected: String): View {
        val dark = prefs.getBoolean("dark", false)
        val backgroundColor = if (dark) Color.rgb(29, 29, 35) else Color.rgb(232, 228, 239)
        val foreground = if (dark) Color.WHITE else Color.rgb(38, 34, 47)
        val card = if (dark) Color.rgb(57, 55, 66) else Color.WHITE
        val accent = Color.rgb(103, 80, 164)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8) + getNavigationBarHeight())
            setBackgroundColor(backgroundColor)
            layoutParams = LinearLayout.LayoutParams(resources.displayMetrics.widthPixels, dp(280))
        }
        root.addView(TextView(this).apply {
            text = "بازبینی متن پیش از ارسال"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(foreground)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))

        val scroll = ScrollView(this)
        val previewContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        previewContent.addView(TextView(this).apply {
            text = "متن فعلی"
            textSize = 12f
            setTextColor(foreground)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(2), dp(4), dp(4))
        })
        previewContent.addView(TextView(this).apply {
            text = original
            textSize = 14f
            setTextColor(foreground)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = createKeyBackground(card)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(8))
        })
        previewContent.addView(TextView(this).apply {
            text = "متن اصلاح‌شده"
            textSize = 12f
            setTextColor(foreground)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(2), dp(4), dp(4))
        })
        previewContent.addView(TextView(this).apply {
            text = corrected
            textSize = 16f
            setTextColor(foreground)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = createKeyBackground(card)
        })
        scroll.addView(previewContent, FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, dp(3), 0, dp(8))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(actionButton("تأیید و ارسال", accent, Color.WHITE) {
            applyCorrectionAndSend()
        }, LinearLayout.LayoutParams(0, dp(46), 1.25f).apply { setMargins(dp(2), 0, dp(2), 0) })
        actions.addView(actionButton("ویرایش", card, foreground) {
            pendingSend = null
            refreshKeyboard()
        }, LinearLayout.LayoutParams(0, dp(46), 0.8f).apply { setMargins(dp(2), 0, dp(2), 0) })
        actions.addView(actionButton("ارسال اصلی", card, foreground) {
            val pending = pendingSend
            pendingSend = null
            if (pending != null) currentInputConnection?.performEditorAction(pending.action)
        }, LinearLayout.LayoutParams(0, dp(46), 0.9f).apply { setMargins(dp(2), 0, dp(2), 0) })
        root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        keyboardRoot = root
        return root
    }

    private fun applyCorrectionAndSend() {
        val pending = pendingSend ?: return
        val ic = currentInputConnection ?: run { pendingSend = null; refreshKeyboard(); return }
        val currentBefore = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val currentAfter = ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()
        if (currentBefore.length != pending.beforeLength || currentAfter.length != pending.afterLength ||
            currentBefore + currentAfter != pending.original) {
            pendingSend = null
            refreshKeyboard()
            return
        }
        try {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(pending.beforeLength, pending.afterLength)
            ic.commitText(pending.corrected, 1)
        } finally {
            ic.endBatchEdit()
        }
        pendingSend = null
        ic.performEditorAction(pending.action)
    }

    private fun getCurrentWord(): String {
        return try {
            val before = currentInputConnection?.getTextBeforeCursor(100, 0)?.toString().orEmpty()
            Regex("[\\p{L}\\p{M}\\p{N}]+$").find(before)?.value.orEmpty()
        } catch (_: Exception) { "" }
    }

    private fun replaceCurrentWord(newWord: String) {
        val oldWord = getCurrentWord()
        if (oldWord.isNotEmpty()) {
            if (!english && !isSensitiveInput()) {
                val context = getLastCompletedWords()
                val closeCandidates = PersianSpellChecker.rank(oldWord, spellCandidates(context)).take(6)
                PersianNeuralContextModel.learnCorrection(prefs, oldWord, newWord, context, closeCandidates)
                learnCorrection(oldWord, newWord)
            }
            currentInputConnection?.deleteSurroundingText(oldWord.length, 0)
        }
        currentInputConnection?.commitText(newWord, 1)
        learnWord(newWord)
        refreshSuggestions()
    }

    private fun rankedSpellMatches(input: String, previousWords: List<String>): List<PersianSpellChecker.Match> {
        val lexical = PersianSpellChecker.rank(input, spellCandidates(previousWords)).toList()
        return PersianNeuralContextModel.rerank(prefs, input, lexical, previousWords)
    }

    private fun spellCandidates(previousWords: List<String>): Sequence<PersianSpellChecker.Candidate> {
        val learned = loadLearnedWords()
        val previousWord = previousWords.firstOrNull().orEmpty()
        val context = loadBigrams().optJSONObject(previousWord)
        val trigramKey = if (previousWords.size >= 2)
            "${PersianSpellChecker.normalize(previousWords[1])}\u001f${PersianSpellChecker.normalize(previousWords[0])}" else ""
        val trigramContext = if (trigramKey.isNotEmpty()) loadTrigrams().optJSONObject(trigramKey) else null
        fun candidate(word: String, frequency: Int) = PersianSpellChecker.Candidate(
            word = word,
            frequency = frequency,
            contextFrequency = context?.optInt(word, 0) ?: 0,
            trigramFrequency = trigramContext?.optInt(PersianSpellChecker.normalize(word), 0) ?: 0
        )
        val learnedCandidates = learned.keys().asSequence().map { w -> candidate(w, learned.optInt(w, 1)) }
        val commonCandidates = commonWords.asSequence().map { w -> candidate(w, 8) }
        val dictionaryCandidates = persianDictionary.asSequence().map { w -> candidate(w, 3) }
        return learnedCandidates + commonCandidates + dictionaryCandidates
    }

    private fun rememberedCorrection(typedWord: String): String? {
        if (isSensitiveInput() || english) return null
        val key = PersianSpellChecker.normalize(typedWord)
        if (key.isBlank()) return null
        return try {
            val entry = loadCorrectionMemory().optJSONObject(key) ?: return null
            val count = entry.optInt("count", 0)
            if (count >= 1) entry.optString("word").takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null }
    }

    private fun learnCorrection(typedWord: String, chosenWord: String) {
        val typed = typedWord.trim()
        val chosen = chosenWord.trim()
        if (typed.length < 2 || chosen.isBlank() || typed.length > 40 || chosen.length > 40) return
        if (typed.any { !it.isLetter() && it != '\u200c' } || chosen.any { !it.isLetter() && it != '\u200c' }) return
        val key = PersianSpellChecker.normalize(typed)
        val value = PersianSpellChecker.normalize(chosen)
        if (key == value) return
        try {
            val memory = loadCorrectionMemory()
            val old = memory.optJSONObject(key) ?: JSONObject()
            val oldWord = old.optString("word")
            val count = if (PersianSpellChecker.normalize(oldWord) == value) old.optInt("count", 0) + 1 else 1
            memory.put(key, JSONObject().put("word", chosen).put("count", count.coerceAtMost(100)))
            prefs.edit().putString("correction_memory", memory.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("KeyFarsi", "correction memory save error", e)
        }
    }

    private fun loadCorrectionMemory(): JSONObject {
        return try { JSONObject(prefs.getString("correction_memory", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    }

    private fun findSuggestions(word: String): List<String> {
        if (word.isBlank()) return emptyList()
        val normalized = word.lowercase()
        val learned = loadLearnedWords()
        val learnedMatches = learned.keys().asSequence().filter { it.lowercase().startsWith(normalized) && it.lowercase() != normalized }.sortedByDescending { learned.optInt(it, 0) }.toList()
        val common = commonWords.filter { it.startsWith(normalized) && it != normalized }
        return (learnedMatches + common).distinct().take(3)
    }

    private fun learnPreviousWord() { learnWord(getCurrentWord().trim()) }

    private fun learnWord(rawWord: String) {
        if (isSensitiveInput()) return
        val word = rawWord.trim()
        if (word.length < 1 || word.length > 40) return
        if (word.any { !it.isLetterOrDigit() && it != '\u200c' }) return
        val words = loadLearnedWords()
        val currentCount = words.optInt(word, 0)
        words.put(word, (currentCount + 1).coerceAtMost(1000))
        prefs.edit().putString("learned_words", words.toString()).apply()
    }

    private fun loadLearnedWords(): JSONObject {
        return try { JSONObject(prefs.getString("learned_words", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    }

    private fun isSensitiveInput(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private val commonWords = listOf(
        "سلام","سلامتی","سلامت","خوب","خوبی","خوبه","ممنون","مرسی","بله","خیر","نه","آره","باشه","حتما","لطفا","خواهش","خواهشاً","دوست","دوستان","دوستت","دارم","ندارم","هستم","هستیم","هستند","امروز","فردا","دیروز","الان","بعدا","بعداً","خونه","خانه","کار","کارت","زندگی","خانواده","میشه","می‌شود","میتونم","می‌توانم","میخوام","می‌خواهم","میکنم","می‌کنم","باید","نباید","چرا","چطور","چگونه","کجا","کی","چی","چه","برای","از","به","با","در","این","اون","آن","همه","هیچ","خیلی","زیاد","کم","بیشتر","فارسی","انگلیسی","کیبورد","گوشی","موبایل","برنامه","پیام","متن","کلمه","حروف","the","this","that","there","their","hello","thanks","thank","you","your","good","great","what","when","where","how","please","have","with","from","will","would","could","should"
    )

    private fun saveClipboardCurrent() {
        try {
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val value = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
            if (value.isBlank()) return
            val list = loadClipboard()
            if ((0 until list.length()).any { list.optJSONObject(it)?.optString("text") == value }) return
            list.put(JSONObject().put("text", value).put("pinned", false))
            val unpinnedIndexes = mutableListOf<Int>()
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                if (!obj.optBoolean("pinned")) unpinnedIndexes.add(i)
            }
            val toRemove = unpinnedIndexes.size - 10
            if (toRemove > 0) {
                val removeSet = unpinnedIndexes.take(toRemove).toSet()
                val newArray = JSONArray()
                for (i in 0 until list.length()) { if (i !in removeSet) newArray.put(list.optJSONObject(i)) }
                saveClipboard(newArray)
            } else saveClipboard(list)
        } catch (e: Exception) { android.util.Log.e("KeyFarsi", "clipboard save error", e) }
    }

    private fun loadClipboard(): JSONArray { return try { JSONArray(prefs.getString("clipboard", "[]")) } catch (_: Exception) { JSONArray() } }
    private fun saveClipboard(array: JSONArray) { prefs.edit().putString("clipboard", array.toString()).apply() }

    private fun toEnglishDigits(value: String): String {
        return value.map { char ->
            when (char) {
                '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                '٫' -> '.'; '،' -> ','; else -> char
            }
        }.joinToString("")
    }
}
