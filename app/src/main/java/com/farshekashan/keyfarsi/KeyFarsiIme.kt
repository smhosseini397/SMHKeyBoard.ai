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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
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
    private var suggestionRunnable: Runnable? = null
    private var pendingSuggestUpdate: Runnable? = null
    private var lastKeyTime = 0L
    private var lastSettingsHash = 0

    private var cachedBigrams: JSONObject? = null
    private var cachedTrigrams: JSONObject? = null
    private var cachedLearnedWords: JSONObject? = null
    private var cachedCorrectionMemory: JSONObject? = null
    private var lastSuggestedContext = ""

    private val prefs by lazy { getSharedPreferences("keyfarsi", MODE_PRIVATE) }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && isRelevantSettingKey(key) && isInputViewShown) {
            handler.post {
                val newHash = computeSettingsHash()
                if (newHash != lastSettingsHash) {
                    lastSettingsHash = newHash
                    setInputView(buildKeyboard())
                }
            }
        }
    }

    private fun isRelevantSettingKey(key: String): Boolean {
        return key in listOf(
            "dark", "theme", "keyboard_padding", "letter_side_margin",
            "merge_key_kaf", "merge_key_kaf_delete",
            "merge_key_re", "merge_key_re_delete",
            "merge_ta_za", "merge_ta_za_delete",
            "merge_sad_zad", "merge_sad_zad_delete",
            "merge_dal_zal", "merge_dal_zal_delete",
            "merge_seen_the", "merge_seen_the_delete",
            "merge_ain_ghain", "merge_ain_ghain_delete",
            "merge_che_je", "merge_che_je_delete",
            "standard_big_keyboard", "fa_key_width", "fa_key_height", "fa_key_gap",
            "en_key_width", "en_key_height", "en_key_gap",
            "keyboard_width", "keyboard_height",
            "keyboard_width_landscape", "keyboard_height_landscape",
            "keyboard_vertical_offset", "backspace_separate_row",
            "backspace_separate_gap",
            "space_weight", "enter_weight",
            "backspace_weight", "backspace_separate_height", "backspace_inline_width",
            "standard_key_width", "standard_key_height", "standard_key_gap", "standard_scale",
            "long_press_delay", "slow_typing_threshold", "number_key_height",
            "auto_correct"
        )
    }

    private val persianDictionary: List<String> by lazy {
        try { assets.open("fa_words.txt").bufferedReader(Charsets.UTF_8).useLines { it.filter { w -> w.isNotBlank() }.toList() } }
        catch (_: Exception) { emptyList() }
    }

    private val clipboard by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val DEFAULT_KEY_WIDTH = 34
    private val DEFAULT_KEY_HEIGHT = 40
    private val DEFAULT_KEY_GAP = 4
    private val DEFAULT_PADDING = 2

    private fun t(key: String): String {
        return when (key) {
            "key_space" -> "فاصله"
            "key_shift" -> "⇧"
            "key_backspace" -> "⌫"
            "key_enter" -> "↵"
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

    private data class MergeSpec(
        val firstChar: Char,
        val secondChar: Char,
        val enableKey: String,
        val deleteKey: String
    )

    private val mergeSpecs = listOf(
        MergeSpec('ک', 'گ', "merge_key_kaf",   "merge_key_kaf_delete"),
        MergeSpec('ر', 'ژ', "merge_key_re",    "merge_key_re_delete"),
        MergeSpec('ط', 'ظ', "merge_ta_za",     "merge_ta_za_delete"),
        MergeSpec('ص', 'ض', "merge_sad_zad",   "merge_sad_zad_delete"),
        MergeSpec('د', 'ذ', "merge_dal_zal",   "merge_dal_zal_delete"),
        MergeSpec('س', 'ث', "merge_seen_the",  "merge_seen_the_delete"),
        MergeSpec('ع', 'غ', "merge_ain_ghain", "merge_ain_ghain_delete"),
        MergeSpec('چ', 'ج', "merge_che_je",    "merge_che_je_delete")
    )

    private fun activeMerges(): Map<Char, Char> {
        val map = mutableMapOf<Char, Char>()
        for (spec in mergeSpecs) {
            if (prefs.getBoolean(spec.enableKey, false)) {
                map[spec.firstChar] = spec.secondChar
            }
        }
        return map
    }

    private fun removedKeys(): Set<Char> {
        val removed = mutableSetOf<Char>()
        for (spec in mergeSpecs) {
            if (prefs.getBoolean(spec.deleteKey, false)) {
                removed.add(spec.secondChar)
            }
        }
        return removed
    }

    private fun mergeLabelFor(char: Char): Char? = activeMerges()[char]

    private val alternateChars: Map<String, List<String>> = mapOf(
        "ا" to listOf("آ", "أ", "ء", "ئ"),
        "a" to listOf("à", "á", "â", "ä", "å", "æ", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"),
        "o" to listOf("ò", "ó", "ô", "ö", "ø", "ō"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń", "ň"),
        "s" to listOf("ś", "š", "ş"),
        "m" to listOf("."),
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
        lastSettingsHash = computeSettingsHash()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) } catch (_: Exception) {}
        stopRepeat()
        suggestionRunnable?.let { handler.removeCallbacks(it) }
        pendingSuggestUpdate?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun computeSettingsHash(): Int {
        val stringKeys = listOf("theme")
        val booleanKeys = listOf(
            "dark", "standard_big_keyboard",
            "merge_key_kaf", "merge_key_kaf_delete",
            "merge_key_re", "merge_key_re_delete",
            "merge_ta_za", "merge_ta_za_delete",
            "merge_sad_zad", "merge_sad_zad_delete",
            "merge_dal_zal", "merge_dal_zal_delete",
            "merge_seen_the", "merge_seen_the_delete",
            "merge_ain_ghain", "merge_ain_ghain_delete",
            "merge_che_je", "merge_che_je_delete",
            "backspace_separate_row", "auto_correct"
        )
        val intKeys = listOf(
            "keyboard_padding", "letter_side_margin",
            "fa_key_width", "fa_key_height", "fa_key_gap",
            "en_key_width", "en_key_height", "en_key_gap",
            "keyboard_width", "keyboard_height",
            "keyboard_width_landscape", "keyboard_height_landscape",
            "keyboard_vertical_offset",
            "space_weight", "enter_weight",
            "backspace_weight", "backspace_separate_height", "backspace_inline_width",
            "backspace_separate_gap",
            "standard_key_width", "standard_key_height", "standard_key_gap", "standard_scale",
            "long_press_delay", "slow_typing_threshold", "number_key_height"
        )
        var h = 17
        for (k in stringKeys) h = 31 * h + (prefs.getString(k, null)?.hashCode() ?: 0)
        for (k in booleanKeys) h = 31 * h + if (prefs.getBoolean(k, false)) 1 else 0
        for (k in intKeys) h = 31 * h + prefs.getInt(k, 0)
        return h
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        pendingSend = null
        lastKeyTime = 0L
        saveClipboardCurrent()
        val newHash = computeSettingsHash()
        if (newHash != lastSettingsHash && isInputViewShown) {
            lastSettingsHash = newHash
            setInputView(buildKeyboard())
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        saveClipboardCurrent()
        val newHash = computeSettingsHash()
        if (newHash != lastSettingsHash && isInputViewShown) {
            lastSettingsHash = newHash
            setInputView(buildKeyboard())
        }
    }

    override fun onCreateInputView(): View {
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        lastSettingsHash = computeSettingsHash()
        return buildKeyboard()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!isInputViewShown) return
        outInsets.contentTopInsets = outInsets.visibleTopInsets
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
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

        val paddingPx = dp(prefs.getInt("keyboard_padding", DEFAULT_PADDING).coerceIn(0, 5))

        val actualWidth = if (isLandscape) {
            if (requestedWidthLandscape > 0) dp(requestedWidthLandscape).coerceAtMost(screenWidth) - (paddingPx * 2) else screenWidth - (paddingPx * 2)
        } else {
            if (requestedWidth > 0) dp(requestedWidth).coerceAtMost(screenWidth) - (paddingPx * 2) else screenWidth - (paddingPx * 2)
        }

        val baseHeight = if (isLandscape) {
            if (requestedHeightLandscape > 0) {
                dp(requestedHeightLandscape).coerceAtMost((screenHeight * 0.55).toInt())
            } else {
                (screenHeight * 0.45).toInt()
            }
        } else {
            if (requestedHeight > 0) {
                dp(requestedHeight).coerceAtMost((screenHeight * 0.55).toInt())
            } else {
                (screenHeight * 0.42).toInt()
            }
        }

        val navBar = if (isLandscape) 0 else getNavigationBarHeight()
        val verticalOffset = prefs.getInt("keyboard_vertical_offset", 0).coerceIn(-100, 200)
        val offsetPx = dp(verticalOffset)
        val adjustedBottomPadding = (navBar + paddingPx + offsetPx).coerceAtLeast(0)

        val heightToUse = if (numberMode || symbolMode || emojiMode || clipboardMode) {
            (baseHeight + adjustedBottomPadding + dp(20)).coerceAtMost((screenHeight * 0.62).toInt())
        } else {
            baseHeight
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(background)
            layoutParams = ViewGroup.LayoutParams(screenWidth, heightToUse)
        }
        keyboardRoot = root

        root.setPadding(paddingPx, paddingPx, paddingPx, adjustedBottomPadding)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(actualWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
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

    // =====================================================================
    // پیشنهادها
    // =====================================================================
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
        suggestionRunnable?.let { handler.removeCallbacks(it) }
        suggestionRunnable = Runnable {
            val current = getCurrentWord()
            val lastWord = getLastCompletedWord()
            val ctx = "$current|$lastWord"
            if (ctx != lastSuggestedContext) {
                lastSuggestedContext = ctx
                doRefreshSuggestions(keyBg, fg)
            }
        }
        handler.postDelayed(suggestionRunnable!!, 180L)
    }

    private fun doRefreshSuggestions(keyBg: Int? = null, fg: Int? = null) {
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
                    PersianSpellChecker.rank(currentWord, spellCandidates(getLastCompletedWords())).take(3).map { m -> m.word }
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
                isFocusable = true
                var longPressTriggered = false
                val longPressRunnable = Runnable {
                    longPressTriggered = true
                    if (word.isNotBlank()) showDeleteLearnedWordDialog(word)
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            longPressTriggered = false
                            handler.postDelayed(longPressRunnable, 500L)
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            handler.removeCallbacks(longPressRunnable)
                            if (!longPressTriggered && word.isNotBlank()) {
                                view.performClick()
                                replaceCurrentWord(word)
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            handler.removeCallbacks(longPressRunnable)
                            true
                        }
                        else -> false
                    }
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        }
    }

    private fun showDeleteLearnedWordDialog(word: String) {
        try {
            val dialog = android.app.AlertDialog.Builder(this@KeyFarsiIme)
                .setTitle("حذف کلمه یادگرفته")
                .setMessage("کلمه «$word» از حافظه یادگیری حذف شود؟")
                .setPositiveButton("حذف") { _, _ ->
                    removeLearnedWord(word)
                    removeBigramsContaining(word)
                    removeTrigramsContaining(word)
                    Toast.makeText(this@KeyFarsiIme, "کلمه «$word» حذف شد", Toast.LENGTH_SHORT).show()
                    lastSuggestedContext = ""
                    refreshSuggestions()
                }
                .setNegativeButton("انصراف", null)
                .create()
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_PHONE
            )
            dialog.show()
        } catch (e: Exception) {
            removeLearnedWord(word)
            removeBigramsContaining(word)
            removeTrigramsContaining(word)
            Toast.makeText(this@KeyFarsiIme, "کلمه «$word» حذف شد", Toast.LENGTH_SHORT).show()
            lastSuggestedContext = ""
            refreshSuggestions()
        }
    }

    private fun removeLearnedWord(word: String) {
        try {
            val words = loadLearnedWords()
            val normalized = PersianSpellChecker.normalize(word)
            val keysToRemove = mutableListOf<String>()
            val keys = words.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (PersianSpellChecker.normalize(k) == normalized) keysToRemove.add(k)
            }
            keysToRemove.forEach { words.remove(it) }
            prefs.edit().putString("learned_words", words.toString()).apply()
            cachedLearnedWords = words
        } catch (e: Exception) {
            android.util.Log.e("KeyFarsi", "remove learned word error", e)
        }
    }

    private fun removeBigramsContaining(word: String) {
        try {
            val normalized = PersianSpellChecker.normalize(word)
            val bigrams = loadBigrams()
            val keys = bigrams.keys()
            val keysToRemove = mutableListOf<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                if (PersianSpellChecker.normalize(k) == normalized) { keysToRemove.add(k); continue }
                val next = bigrams.optJSONObject(k) ?: continue
                val nextKeys = next.keys()
                while (nextKeys.hasNext()) {
                    val nk = nextKeys.next()
                    if (PersianSpellChecker.normalize(nk) == normalized) next.remove(nk)
                }
            }
            keysToRemove.forEach { bigrams.remove(it) }
            prefs.edit().putString("bigrams", bigrams.toString()).apply()
            cachedBigrams = bigrams
        } catch (_: Exception) {}
    }

    private fun removeTrigramsContaining(word: String) {
        try {
            val normalized = PersianSpellChecker.normalize(word)
            val trigrams = loadTrigrams()
            val keys = trigrams.keys()
            val keysToRemove = mutableListOf<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.split('\u001f').any { PersianSpellChecker.normalize(it) == normalized }) { keysToRemove.add(k); continue }
                val next = trigrams.optJSONObject(k) ?: continue
                if (next.has(normalized)) next.remove(normalized)
            }
            keysToRemove.forEach { trigrams.remove(it) }
            prefs.edit().putString("trigrams", trigrams.toString()).apply()
            cachedTrigrams = trigrams
        } catch (_: Exception) {}
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
                cachedTrigrams = trimmed
            } else {
                prefs.edit().putString("trigrams", trigrams.toString()).apply()
                cachedTrigrams = trigrams
            }
        } catch (e: Exception) { android.util.Log.e("KeyFarsi", "trigram save error", e) }
    }

    private fun loadTrigrams(): JSONObject {
        if (cachedTrigrams == null) {
            cachedTrigrams = try { JSONObject(prefs.getString("trigrams", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        }
        return cachedTrigrams!!
    }

    private fun saveBigram(prevWord: String, currentWord: String) {
        if (prevWord.isBlank() || currentWord.isBlank()) return
        try {
            val bigrams = loadBigrams()
            val nextWords = bigrams.optJSONObject(prevWord) ?: JSONObject()
            val count = nextWords.optInt(currentWord, 0)
            nextWords.put(currentWord, (count + 1).coerceAtMost(100))
            bigrams.put(prevWord, nextWords)
            prefs.edit().putString("bigrams", bigrams.toString()).apply()
            cachedBigrams = bigrams
        } catch (e: Exception) {
            android.util.Log.e("KeyFarsi", "bigram save error", e)
        }
    }

    private fun loadBigrams(): JSONObject {
        if (cachedBigrams == null) {
            cachedBigrams = try { JSONObject(prefs.getString("bigrams", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        }
        return cachedBigrams!!
    }

    // =====================================================================
    // کیبورد حروف
    // =====================================================================
    private fun buildLetterKeyboard(root: LinearLayout, keyBg: Int, fg: Int, accent: Int, actualWidth: Int) {
        val letterSideMargin = prefs.getInt("letter_side_margin", 4).coerceIn(-120, 40)
        val usableWidthPx = if (letterSideMargin >= 0) {
            (actualWidth - dp(letterSideMargin * 2)).coerceAtLeast(dp(100))
        } else {
            val expanded = actualWidth + dp(-letterSideMargin * 2)
            expanded.coerceAtMost(actualWidth)
        }

        val prefix = if (english) "en_" else "fa_"
        val rawKeyWidth = prefs.getInt("${prefix}key_width", 0)
        val rawKeyHeight = prefs.getInt("${prefix}key_height", 0)
        val rawGap = prefs.getInt("${prefix}key_gap", 0)

        val configuredKeyWidth = if (rawKeyWidth > 0) rawKeyWidth else DEFAULT_KEY_WIDTH
        val configuredGap = if (rawGap > 0) rawGap else DEFAULT_KEY_GAP

        val backspaceSeparate = prefs.getBoolean("backspace_separate_row", false)
        val removed = if (!english) removedKeys() else emptySet()

        val screenHeight = resources.displayMetrics.heightPixels
        val currentRootHeight = keyboardRoot?.layoutParams?.height ?: (screenHeight * 0.42).toInt()
        val availableForKeyArea = currentRootHeight - dp(90)

        val dynamicKeyHeight = if (rawKeyHeight > 0) {
            dp(rawKeyHeight)
        } else {
            if (isLandscape) {
                (availableForKeyArea / 5).coerceIn(dp(20), dp(36))
            } else {
                if (prefs.getBoolean("standard_big_keyboard", false)) {
                    (availableForKeyArea / 4).coerceIn(dp(28), dp(48))
                } else {
                    (availableForKeyArea / 5).coerceIn(dp(28), dp(44))
                }
            }
        }

        val standardBig = prefs.getBoolean("standard_big_keyboard", false)
        if (standardBig) {
            buildStandardGboardLayout(root, keyBg, fg, accent, usableWidthPx, dynamicKeyHeight, backspaceSeparate)
        } else {
            buildCompactLayout(root, keyBg, fg, accent, usableWidthPx, configuredKeyWidth, dynamicKeyHeight, configuredGap, backspaceSeparate, removed)
        }
    }

    private fun buildStandardGboardLayout(
        root: LinearLayout, keyBg: Int, fg: Int, accent: Int,
        usableWidth: Int, keyHeight: Int, backspaceSeparate: Boolean
    ) {
        val rows = if (english) englishRows else persianRows
        val removed = if (!english) removedKeys() else emptySet()
        val gapPx = dp(prefs.getInt(if (english) "en_key_gap" else "fa_key_gap", DEFAULT_KEY_GAP).let { if (it > 0) it else DEFAULT_KEY_GAP })
        val availableForKeyArea = usableWidth - (10 * gapPx)
        val columnCount = 11
        val keyWidthPx = (availableForKeyArea / columnCount).coerceAtLeast(dp(20))
        val bsWidthPx = (keyWidthPx * 1.3).toInt()

        rows.forEachIndexed { rowIndex, rowStr ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val chars = rowStr.toList().filter { it !in removed }

            if (rowIndex == 0) {
                chars.forEach { item ->
                    val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                    line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeight, gapPx) { commit(v) })
                }
            } else if (rowIndex == 1) {
                chars.take(10).forEach { item ->
                    val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                    line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeight, gapPx) { commit(v) })
                }
            } else {
                val lettersContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                chars.drop(10).forEach { item ->
                    val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                    lettersContainer.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeight, gapPx) { commit(v) })
                }
                line.addView(lettersContainer)

                if (!backspaceSeparate) {
                    val bs = backspaceKeySized(Color.rgb(190, 155, 215), Color.WHITE, bsWidthPx, keyHeight, 0)
                    bs.layoutParams = LinearLayout.LayoutParams(bsWidthPx, keyHeight).apply {
                        setMargins(gapPx, dp(2), 0, dp(2))
                    }
                    line.addView(bs)
                }
            }
            root.addView(line)
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val spaceWeight = prefs.getInt("space_weight", 3).coerceIn(1, 8).toFloat()
        val enterWeight = prefs.getInt("enter_weight", 15).coerceIn(10, 25) / 10f
        bottom.addView(actionButton("?123", keyBg, fg) { toggleMode { numberMode = true } }, LinearLayout.LayoutParams(0, keyHeight, 1.5f).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
        bottom.addView(actionButton(t("key_globe"), keyBg, fg) { english = !english; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeight, 1f).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
        if (english) bottom.addView(actionButton(t("key_shift"), keyBg, fg) { uppercase = !uppercase; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeight, 1f).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
        bottom.addView(keyButton(t("key_space"), keyBg, fg) { commit(" ") }, LinearLayout.LayoutParams(0, keyHeight, spaceWeight).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
        bottom.addView(actionButton(t("key_enter"), accent, Color.WHITE) { sendEnter() }, LinearLayout.LayoutParams(0, keyHeight, enterWeight).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) })
        root.addView(bottom)

        if (backspaceSeparate) {
            val bsGap = prefs.getInt("backspace_separate_gap", 8).coerceIn(0, 40)
            val bsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(bsGap), 0, 0)
            }
            val bsHeight = prefs.getInt("backspace_separate_height", 0).let { if (it > 0) dp(it) else keyHeight }
            val bsWeight = prefs.getInt("backspace_weight", 2).coerceIn(1, 5).toFloat()
            val bs = backspaceKeySized(Color.rgb(190, 155, 215), Color.WHITE, 0, bsHeight, 0)
            bs.layoutParams = LinearLayout.LayoutParams(0, bsHeight, bsWeight).apply {
                setMargins(gapPx, dp(2), 0, dp(2))
            }
            bsRow.addView(bs)
            root.addView(bsRow)
        }
    }

    private fun buildCompactLayout(
        root: LinearLayout, keyBg: Int, fg: Int, accent: Int,
        usableWidth: Int, configuredKeyWidth: Int, keyHeight: Int,
        configuredGap: Int, backspaceSeparate: Boolean, removed: Set<Char>
    ) {
        val rows = if (english) englishRows else persianRows
        val keyWidthPx = dp(configuredKeyWidth)
        val gapPx = dp(configuredGap)
        val maxColumns = ((usableWidth + gapPx) / (keyWidthPx + gapPx * 2)).coerceAtLeast(1)

        val allChars = mutableListOf<Char>()
        rows.forEach { row -> row.forEach { ch -> if (ch !in removed) allChars.add(ch) } }
        val chunks = allChars.chunked(maxColumns)

        val bsInlineMultiplier = prefs.getInt("backspace_inline_width", 15).coerceIn(5, 30) / 10f
        val bsInlineWidth = (keyWidthPx * bsInlineMultiplier).toInt()

        chunks.forEachIndexed { index, chunk ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val emptyCount = maxColumns - chunk.size
            val emptyLeft = emptyCount / 2
            val emptyRight = emptyCount - emptyLeft

            if (index == chunks.lastIndex) {
                repeat(emptyLeft) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeight, gapPx)) }
                chunk.forEach { item ->
                    val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                    line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeight, gapPx) { commit(v) })
                }
                if (!backspaceSeparate) {
                    // ✅ spacer کشسان تا backspace به لبه راست چسبیده شود
                    val spacer = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }
                    line.addView(spacer)
                    val bs = backspaceKeySized(Color.rgb(190, 155, 215), Color.WHITE, bsInlineWidth, keyHeight, 0)
                    bs.layoutParams = LinearLayout.LayoutParams(bsInlineWidth, keyHeight).apply {
                        setMargins(gapPx, dp(2), gapPx, dp(2))
                    }
                    line.addView(bs)
                } else {
                    repeat(emptyRight) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeight, gapPx)) }
                }
            } else {
                repeat(emptyLeft) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeight, gapPx)) }
                chunk.forEach { item ->
                    val v = if (english && uppercase) item.uppercaseChar().toString() else item.toString()
                    line.addView(letterKeySized(v, keyBg, fg, keyWidthPx, keyHeight, gapPx) { commit(v) })
                }
                repeat(emptyRight) { line.addView(emptyFixedKeySized(keyWidthPx, keyHeight, gapPx)) }
            }
            root.addView(line)
        }

        buildBottomRow(root, keyBg, fg, keyHeight)
        if (backspaceSeparate) {
            val bsGap = prefs.getInt("backspace_separate_gap", 8).coerceIn(0, 40)
            val bsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(bsGap), 0, 0)
            }
            val bsHeight = prefs.getInt("backspace_separate_height", 0).let { if (it > 0) dp(it) else keyHeight }
            val bsWeight = prefs.getInt("backspace_weight", 2).coerceIn(1, 5).toFloat()
            val bs = backspaceKeySized(Color.rgb(190, 155, 215), Color.WHITE, 0, bsHeight, 0)
            bs.layoutParams = LinearLayout.LayoutParams(0, bsHeight, bsWeight).apply {
                setMargins(gapPx, dp(2), 0, dp(2))
            }
            bsRow.addView(bs)
            root.addView(bsRow)
        }
    }

    private fun letterKeySized(text: String, bg: Int, fg: Int, widthPx: Int, heightPx: Int, gapPx: Int, click: () -> Unit): View {
        val primaryChar = text.firstOrNull() ?: ' '
        val mergeSecondary = mergeLabelFor(primaryChar)

        val alternates = when {
            mergeSecondary != null -> listOf(mergeSecondary.toString())
            else -> alternateChars[text.lowercase()] ?: alternateChars[text]
        }
        val longPressDelay = prefs.getInt("long_press_delay", 400).coerceIn(150, 1200).toLong()
        val hasLongPress = !alternates.isNullOrEmpty()

        val base = TextView(this).apply {
            if (mergeSecondary != null && text.isNotEmpty()) {
                val secondaryText = mergeSecondary.toString()
                val fullText = "$text\n$secondaryText"
                val spannable = SpannableString(fullText)
                val startIdx = text.length + 1
                if (startIdx < fullText.length) {
                    spannable.setSpan(RelativeSizeSpan(0.6f), startIdx, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(
                        ForegroundColorSpan(if (prefs.getBoolean("dark", false)) Color.rgb(160, 160, 160) else Color.rgb(140, 140, 140)),
                        startIdx, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                this.text = spannable
            } else {
                this.text = text
            }
            textSize = 18f
            setTextColor(fg)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            maxLines = 2
            setPadding(dp(2), dp(1), dp(2), dp(3))
            background = createKeyBackground(bg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) }

            isClickable = true
            isFocusable = true

            var longPressTriggered = false
            var longPressRunnable: Runnable? = null
            var downX = 0f
            var downY = 0f
            var executed = false
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressTriggered = false
                        executed = false
                        downX = event.x
                        downY = event.y
                        view.animate().translationY(dp(2).toFloat()).setDuration(40).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dp(1).toFloat()

                        if (hasLongPress) {
                            longPressRunnable = Runnable {
                                longPressTriggered = true
                                executed = true
                                view.animate().translationY(0f).setDuration(60).start()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dp(3).toFloat()
                                if (alternates!!.size == 1) commit(alternates[0])
                                else showAlternativesPopup(view, alternates)
                            }
                            handler.postDelayed(longPressRunnable!!, longPressDelay)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!longPressTriggered) {
                            val dx = abs(event.x - downX)
                            val dy = abs(event.y - downY)
                            if (dx > touchSlop || dy > touchSlop) {
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = null
                        view.animate().translationY(0f).setDuration(60).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.animate().setDuration(60).withEndAction { view.elevation = dp(3).toFloat() }.start()
                        }
                        if (!longPressTriggered && !executed) {
                            executed = true
                            view.performClick()
                            click()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = null
                        view.animate().translationY(0f).setDuration(60).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.animate().setDuration(60).withEndAction { view.elevation = dp(3).toFloat() }.start()
                        }
                        // ✅ اگر حرکت قابل توجه نبود و long-press رخ نداده، commit کن
                        if (!longPressTriggered && !executed) {
                            executed = true
                            view.performClick()
                            click()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        return base
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
            }
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { setMargins(gapPx, dp(2), gapPx, dp(2)) }
            isClickable = true
            isFocusable = true

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().translationY(dp(2).toFloat()).setDuration(40).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dp(1).toFloat()
                        deleteOneChar()
                        startRepeat()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().translationY(0f).setDuration(60).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.animate().setDuration(60).withEndAction { view.elevation = dp(3).toFloat() }.start()
                        }
                        stopRepeat()
                        true
                    }
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
        lastSuggestedContext = ""
        refreshSuggestions()
    }

    private fun buildBottomRow(root: LinearLayout, keyBg: Int, fg: Int, keyHeight: Int) {
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val spaceWeight = prefs.getInt("space_weight", 3).coerceIn(1, 8).toFloat()
        val enterWeight = prefs.getInt("enter_weight", 15).coerceIn(10, 25) / 10f
        bottom.addView(actionButton("?123", keyBg, fg) { toggleMode { numberMode = true } }, LinearLayout.LayoutParams(0, keyHeight, 1.5f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        bottom.addView(actionButton(t("key_globe"), keyBg, fg) { english = !english; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeight, 1f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        if (english) bottom.addView(actionButton(t("key_shift"), keyBg, fg) { uppercase = !uppercase; refreshKeyboard() }, LinearLayout.LayoutParams(0, keyHeight, 1f).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        bottom.addView(keyButton(t("key_space"), keyBg, fg) { commit(" ") }, LinearLayout.LayoutParams(0, keyHeight, spaceWeight).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        bottom.addView(actionButton(t("key_enter"), Color.rgb(103, 80, 164), Color.WHITE) { sendEnter() }, LinearLayout.LayoutParams(0, keyHeight, enterWeight).apply { setMargins(dp(gap()), dp(2), dp(gap()), dp(2)) })
        root.addView(bottom)
    }

    private fun buildNumberPad(root: LinearLayout, keyBg: Int, fg: Int) {
        val rawNumHeight = prefs.getInt("number_key_height", 0)
        val fallbackFaHeight = prefs.getInt("fa_key_height", 0)
        val baseNumHeightDp = when {
            rawNumHeight > 0 -> rawNumHeight
            fallbackFaHeight > 0 -> fallbackFaHeight
            else -> if (isLandscape) 34 else 44
        }
        val numKeyHeight = dp(
            if (isLandscape) baseNumHeightDp.coerceIn(22, 56)
            else baseNumHeightDp.coerceIn(30, 60)
        )

        val faGapRaw = prefs.getInt("fa_key_gap", 0)
        val numGap = dp(if (faGapRaw > 0) faGapRaw else DEFAULT_KEY_GAP)

        val faKeyWidthRaw = prefs.getInt("fa_key_width", 0)
        val numKeyWidthConfigured = if (faKeyWidthRaw > 0) dp(faKeyWidthRaw) else 0

        val rows = listOf(
            listOf("۱", "۲", "۳"),
            listOf("۴", "۵", "۶"),
            listOf("۷", "۸", "۹")
        )
        rows.forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { value ->
                val display = if (english) toEnglishDigits(value) else value
                val cellView = keyButton(display, keyBg, fg, 20f) { commit(if (english) toEnglishDigits(value) else value) }
                val lp = if (numKeyWidthConfigured > 0) {
                    LinearLayout.LayoutParams(numKeyWidthConfigured, numKeyHeight).apply {
                        setMargins(numGap, dp(2), numGap, dp(2))
                    }
                } else {
                    LinearLayout.LayoutParams(0, numKeyHeight, 1f).apply {
                        setMargins(numGap, dp(2), numGap, dp(2))
                    }
                }
                line.addView(cellView, lp)
            }
            root.addView(line)
        }

        val lastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val zeroDisplay = if (english) "0" else "۰"
        val zeroView = keyButton(zeroDisplay, keyBg, fg, 20f) { commit(if (english) "0" else "۰") }
        val zeroLp = if (numKeyWidthConfigured > 0) {
            LinearLayout.LayoutParams(numKeyWidthConfigured * 2, numKeyHeight).apply {
                setMargins(numGap, dp(2), numGap, dp(2))
            }
        } else {
            LinearLayout.LayoutParams(0, numKeyHeight, 2f).apply {
                setMargins(numGap, dp(2), numGap, dp(2))
            }
        }
        lastRow.addView(zeroView, zeroLp)

        val bs = backspaceKeySized(keyBg, fg, 0, numKeyHeight, 0)
        val bsLp = if (numKeyWidthConfigured > 0) {
            LinearLayout.LayoutParams(numKeyWidthConfigured, numKeyHeight).apply {
                setMargins(numGap, dp(2), numGap, dp(2))
            }
        } else {
            LinearLayout.LayoutParams(0, numKeyHeight, 1f).apply {
                setMargins(numGap, dp(2), numGap, dp(2))
            }
        }
        bs.layoutParams = bsLp
        lastRow.addView(bs)
        root.addView(lastRow)

        val symbolsRow = listOf("؟","%","@","×","+","_","=",".")
        val symLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        symbolsRow.forEach { s ->
            symLine.addView(
                keyButton(s, keyBg, fg, 16f) { commit(s) },
                LinearLayout.LayoutParams(0, numKeyHeight, 1f).apply { setMargins(numGap, dp(2), numGap, dp(2)) }
            )
        }
        root.addView(symLine)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(actionButton(t("mode_letters"), keyBg, fg) { toggleMode { numberMode = false } }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(numGap, dp(2), numGap, dp(2)) })
        controls.addView(actionButton(t("mode_symbols"), keyBg, fg) { toggleMode { symbolMode = true } }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(numGap, dp(2), numGap, dp(2)) })
        controls.addView(actionButton(t("mode_emoji"), keyBg, fg) { toggleMode { emojiMode = true } }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(numGap, dp(2), numGap, dp(2)) })
        controls.addView(keyButton(" ", keyBg, fg) { commit(" ") }, LinearLayout.LayoutParams(0, numKeyHeight, 2f).apply { setMargins(numGap, dp(2), numGap, dp(2)) })
        controls.addView(actionButton(t("key_enter"), Color.rgb(103, 80, 164), Color.WHITE) { sendEnter() }, LinearLayout.LayoutParams(0, numKeyHeight, 1.5f).apply { setMargins(numGap, dp(2), numGap, dp(2)) })
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
        val symbolHeight = if (isLandscape) (resources.displayMetrics.heightPixels * 0.5).toInt() else dp(240)
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, symbolHeight))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(actionButton(t("mode_numbers"), keyBg, fg) { toggleMode { numberMode = true } })
        controls.addView(actionButton(t("mode_emoji"), keyBg, fg) { toggleMode { emojiMode = true } })
        controls.addView(actionButton(t("mode_letters"), Color.rgb(103, 80, 164), Color.WHITE) { toggleMode { } })
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
        val emojiHeight = if (isLandscape) (resources.displayMetrics.heightPixels * 0.5).toInt() else dp(240)
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, emojiHeight))

        root.addView(actionButton(t("mode_back_to_letters"), Color.rgb(103, 80, 164), Color.WHITE) { toggleMode { } })
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
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton(t("clipboard_save_current"), keyBg, fg) { saveClipboardCurrent(); refreshKeyboard() })
        actions.addView(actionButton(t("mode_back"), Color.rgb(103, 80, 164), Color.WHITE) { toggleMode { } })
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(3).toFloat()
            }
            isClickable = true
            isFocusable = true

            var downX = 0f
            var downY = 0f
            var executed = false
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        executed = false
                        downX = event.x
                        downY = event.y
                        view.animate().translationY(dp(2).toFloat()).setDuration(40).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dp(1).toFloat()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // فقط برای سازگاری، اجرا در UP/CANCEL انجام می‌شود
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.animate().translationY(0f).setDuration(60).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.animate().setDuration(60).withEndAction { view.elevation = dp(3).toFloat() }.start()
                        }
                        if (!executed) {
                            executed = true
                            view.performClick()
                            click()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.animate().translationY(0f).setDuration(60).start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.animate().setDuration(60).withEndAction { view.elevation = dp(3).toFloat() }.start()
                        }
                        // ✅ اگر حرکت قابل توجه نبود، commit کن
                        if (!executed) {
                            executed = true
                            view.performClick()
                            click()
                        }
                        true
                    }
                    else -> false
                }
            }
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
        suggestionRunnable?.let { handler.removeCallbacks(it) }
        pendingSuggestUpdate?.let { handler.removeCallbacks(it) }
        lastSuggestedContext = ""
        lastSettingsHash = computeSettingsHash()
        setInputView(buildKeyboard())
    }

    private fun commit(value: String) {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        val timeSinceLastKey = if (lastKeyTime == 0L) Long.MAX_VALUE else now - lastKeyTime
        lastKeyTime = now

        val isWordBoundary = value == " " || value == "\n" || value in listOf(".", ",", "!", "؟", "؛")
        if (!isWordBoundary) {
            val finalValue = if (isNumericInput()) toEnglishDigits(value) else value
            ic.commitText(finalValue, 1)

            pendingSuggestUpdate?.let { handler.removeCallbacks(it) }
            pendingSuggestUpdate = Runnable { refreshSuggestions() }
            handler.postDelayed(pendingSuggestUpdate!!, 250L)
            return
        }

        val currentWord = getCurrentWord()
        val priorWords = getLastCompletedWords()
        val lastWord = priorWords.firstOrNull().orEmpty()

        var correctedCurrentWord = currentWord
        val autoCorrect = prefs.getBoolean("auto_correct", true)
        val slowTypingThreshold = prefs.getInt("slow_typing_threshold", 1500).coerceIn(500, 5000).toLong()
        val isSlowTyping = timeSinceLastKey > slowTypingThreshold

        if (currentWord.length >= 2 && !english && !isSensitiveInput() && value == " ") {
            if (isKnownCorrectWord(currentWord)) learnWord(currentWord)
        }

        if (autoCorrect && value == " " && currentWord.length >= 2 && !english && !isSensitiveInput() && !isSlowTyping) {
            val ranked = PersianSpellChecker.rank(currentWord, spellCandidates(priorWords))
            val correction = rememberedCorrection(currentWord)
                ?: PersianSpellChecker.confidentCorrection(currentWord, spellCandidates(priorWords), ranked)
            if (correction != null && PersianSpellChecker.normalize(correction) != PersianSpellChecker.normalize(currentWord)) {
                ic.deleteSurroundingText(currentWord.length, 0)
                ic.commitText(correction, 1)
                correctedCurrentWord = correction
                learnWord(correction)
            }
        }
        if (lastWord.isNotBlank() && correctedCurrentWord.isNotBlank()) {
            saveBigram(lastWord, correctedCurrentWord)
            if (priorWords.size >= 2) saveTrigram(priorWords[1], priorWords[0], correctedCurrentWord)
        }
        learnWord(correctedCurrentWord)

        ic.commitText(value, 1)
        if (value == " ") uppercase = false

        pendingSuggestUpdate?.let { handler.removeCallbacks(it) }
        pendingSuggestUpdate = Runnable { refreshSuggestions() }
        handler.postDelayed(pendingSuggestUpdate!!, 120L)
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
        if (isSensitiveInput()) { ic.performEditorAction(action); return }
        if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.performEditorAction(action); return }

        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()
        val original = before + after
        if (original.isBlank() || original.length > 4000) { ic.performEditorAction(action); return }

        val corrected = correctWholePersianText(original)
        if (corrected == original) { ic.performEditorAction(action); return }

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
                    val ranked = PersianSpellChecker.rank(word, spellCandidates(previousWords.takeLast(2).reversed()))
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
                learnCorrection(oldWord, newWord)
            }
            currentInputConnection?.deleteSurroundingText(oldWord.length, 0)
        }
        currentInputConnection?.commitText(newWord, 1)
        currentInputConnection?.commitText(" ", 1)
        learnWord(newWord)
        lastSuggestedContext = ""
        refreshSuggestions()
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
            cachedCorrectionMemory = memory
        } catch (e: Exception) {
            android.util.Log.e("KeyFarsi", "correction memory save error", e)
        }
    }

    private fun loadCorrectionMemory(): JSONObject {
        if (cachedCorrectionMemory == null) {
            cachedCorrectionMemory = try { JSONObject(prefs.getString("correction_memory", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        }
        return cachedCorrectionMemory!!
    }

    private fun findSuggestions(word: String): List<String> {
        if (word.isBlank()) return emptyList()
        val normalized = word.lowercase()
        val learned = loadLearnedWords()
        val learnedMatches = learned.keys().asSequence().filter { it.lowercase().startsWith(normalized) && it.lowercase() != normalized }.sortedByDescending { learned.optInt(it, 0) }.toList()
        val common = commonWords.filter { it.startsWith(normalized) && it != normalized }
        return (learnedMatches + common).distinct().take(3)
    }

    private fun learnWord(rawWord: String) {
        if (isSensitiveInput()) return
        val word = rawWord.trim()
        if (word.length < 1 || word.length > 40) return
        if (word.any { !it.isLetterOrDigit() && it != '\u200c' }) return
        val words = loadLearnedWords()
        val currentCount = words.optInt(word, 0)
        words.put(word, (currentCount + 1).coerceAtMost(1000))
        prefs.edit().putString("learned_words", words.toString()).apply()
        cachedLearnedWords = words
    }

    private fun loadLearnedWords(): JSONObject {
        if (cachedLearnedWords == null) {
            cachedLearnedWords = try { JSONObject(prefs.getString("learned_words", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        }
        return cachedLearnedWords!!
    }

    private val normalizedDictionary: Set<String> by lazy {
        (commonWords + persianDictionary).map { PersianSpellChecker.normalize(it) }.toSet()
    }

    private fun isKnownCorrectWord(word: String): Boolean {
        val normalized = PersianSpellChecker.normalize(word)
        if (normalized.isBlank()) return false
        val learned = loadLearnedWords()
        if (learned.has(word)) return true
        val learnedKeys = learned.keys()
        while (learnedKeys.hasNext()) {
            if (PersianSpellChecker.normalize(learnedKeys.next()) == normalized) return true
        }
        return normalized in normalizedDictionary
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
