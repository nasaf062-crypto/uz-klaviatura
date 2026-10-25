package uz.klaviatura

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

class UzKeyboardService : InputMethodService() {

    val handler = Handler(Looper.getMainLooper())
    lateinit var root: LinearLayout
    lateinit var prefs: SharedPreferences
    var mode = 0
    var shift = false
    var smart = true

    val uni = HashMap<String, Stat>()
    val bi = HashMap<String, Stat>()
    var dirty = false
    val saver = Runnable { saveDict() }
    val upd = Runnable {
        refreshShift()
        updateSuggestions()
    }
    val sugViews = arrayOfNulls<TextView>(3)

    val clips = ArrayList<ClipItem>()
    var clipMgr: ClipboardManager? = null
    val clipListener = ClipboardManager.OnPrimaryClipChangedListener { grabClip() }
    val ttl = 2L * 60L * 60L * 1000L

    val cBg = Color.parseColor("#1B1B1B")
    val cKey = Color.parseColor("#3A3A3A")
    val cFn = Color.parseColor("#505050")
    val cNew = Color.parseColor("#1F5A3D")
    val cAcc = Color.parseColor("#4C8DF6")
    val cPop = Color.parseColor("#3D3D3D")

    private val NORMAL = 0
    private val FN = 1
    private val NEWK = 2
    private val ACC = 3

    private val abcRows = listOf(
        listOf("q", "ş*", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ö*"),
        listOf("SH", "z", "x", "ç*", "v", "b", "n", "m", "BS")
    )
    private val numRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "\$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("SYMB", "*", "\"", "'", ":", ";", "!", "?", "BS")
    )
    private val symRows = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "Δ"),
        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\"),
        listOf("NUMB", "%", "©", "®", "™", "✓", "[", "]", "BS")
    )
    private val punct = listOf(
        "&", "%", "+", "\"", "-", ":", "'", "@",
        ";", "/", "(", ")", "#", "!", ",", "?"
    )
    private val altsMap = mapOf(
        "^" to listOf("↑", "←", "↓", "→"),
        "+" to listOf("₊", "⁺", "±"),
        "=" to listOf("≠", "∞", "≈"),
        "-" to listOf("—", "₋", "⁻"),
        "#" to listOf("№")
    )
    private val sub = "₀₁₂₃₄₅₆₇₈₉"
    private val sup = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("uz_kb", Context.MODE_PRIVATE)
        loadDict()
        clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipMgr?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipMgr?.removePrimaryClipChangedListener(clipListener)
        saveDict()
        super.onDestroy()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        saveDict()
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(3), dp(2), dp(3), dp(10))
        root.isMotionEventSplittingEnabled = true
        render()
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val typ = info?.inputType ?: 0
        val cls = typ and InputType.TYPE_MASK_CLASS
        val v = typ and InputType.TYPE_MASK_VARIATION
        smart = cls == InputType.TYPE_CLASS_TEXT &&
            v != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
            v != InputType.TYPE_TEXT_VARIATION_URI
        if (!::root.isInitialized) return
        if (!restarting) mode = 0
        shift = mode == 0 && wantCaps()
        render()
    }

    fun requestRender() {
        handler.post { render() }
    }

    fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun render() {
        root.removeAllViews()
        root.addView(buildBar())
        if (mode == 3) {
            buildClipPanel()
        } else {
            val rows = when (mode) {
                0 -> abcRows
                1 -> numRows
                else -> symRows
            }
            for (r in rows) root.addView(buildRow(r))
            val bottom = when (mode) {
                0 -> listOf("NUM", "ʼ", "SP", "DOT", "ENT")
                1 -> listOf("ABC", ",", "SP", "DOT", "ENT")
                else -> listOf("ABC", "<", "SP", ">", "ENT")
            }
            root.addView(buildRow(bottom))
        }
        updateSuggestions()
    }

    private fun buildBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until 3) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setPadding(dp(6), 0, dp(6), 0)
            tv.setOnClickListener {
                val w = tv.text.toString()
                if (w.isNotEmpty()) pickSuggestion(w)
            }
            bar.addView(tv, LinearLayout.LayoutParams(0, dp(42), 1f))
            sugViews[i] = tv
        }
        val clipBtn = TextView(this)
        clipBtn.text = "📋"
        clipBtn.textSize = 20f
        clipBtn.gravity = Gravity.CENTER
        clipBtn.setOnClickListener {
            mode = if (mode == 3) 0 else 3
            shift = mode == 0 && wantCaps()
            requestRender()
        }
        bar.addView(clipBtn, LinearLayout.LayoutParams(dp(52), dp(42)))
        return bar
    }

    private fun buildClipPanel() {
        prune()
        if (clips.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Bufer bo'sh. Nusxa olgan matnlaringiz shu yerda 2 soat turadi."
            tv.setTextColor(Color.LTGRAY)
            tv.textSize = 15f
            tv.gravity = Gravity.CENTER
            root.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
        }
        for (c in clips.take(5)) {
            val tv = TextView(this)
            tv.text = c.text.replace("\n", " ↵ ")
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setPadding(dp(14), 0, dp(14), 0)
            tv.background = keyBg(cKey)
            tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            tv.setOnClickListener {
                currentInputConnection?.commitText(c.text, 1)
                mode = 0
                shift = false
                requestRender()
                scheduleUpdate()
            }
            root.addView(tv)
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(fnKey("ABC", 1.4f) { mode = 0; shift = wantCaps(); requestRender() })
        row.addView(fnKey("Tozalash", 3f) { clips.clear(); requestRender() })
        root.addView(row)
    }

    private fun buildRow(tokens: List<String>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.isMotionEventSplittingEnabled = true
        for (t in tokens) row.addView(buildKey(t))
        return row
    }

    private fun buildKey(tok: String): View = when (tok) {
        "GAP" -> spacer(1.4f)
        "SH" -> shiftKey()
        "BS" -> bsKey()
        "NUM", "NUMB" -> fnKey("?123", 1.4f) { mode = 1; shift = false; requestRender() }
        "ABC" -> fnKey("ABC", 1.4f) { mode = 0; shift = wantCaps(); requestRender() }
        "SYMB" -> fnKey("=\\<", 1.4f) { mode = 2; requestRender() }
        "SP" -> spaceKey()
        "ENT" -> enterKey()
        "DOT" -> dotKey()
        else -> charKey(tok)
    }

    fun roundBg(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    fun keyBg(color: Int): InsetDrawable =
        InsetDrawable(roundBg(color, 8), dp(2), dp(3), dp(2), dp(3))

    fun keyView(label: String, kind: Int, weight: Float): TextView {
        val color = when (kind) {
            FN -> cFn
            NEWK -> cNew
            ACC -> cAcc
            else -> cKey
        }
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.textSize = if (kind == FN) 16f else 20f
        t.background = keyBg(color)
        t.layoutParams = LinearLayout.LayoutParams(0, dp(58), weight)
        return t
    }

    private fun spacer(weight: Float): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(0, dp(58), weight)
        return v
    }

    private fun fnKey(label: String, w: Float, tap: () -> Unit): View {
        val v = keyView(label, FN, w)
        bindKey(v, tap, null)
        return v
    }

    private fun shiftKey(): View {
        val v = keyView("⇧", if (shift) ACC else FN, 1.4f)
        bindKey(v, { shift = !shift; requestRender() }, null)
        return v
    }

    private fun spaceKey(): View {
        val v = keyView("probel", NORMAL, 5f)
        v.textSize = 14f
        bindKey(v, { typeRaw(" ") }, null)
        return v
    }

    private fun enterKey(): View {
        val v = keyView("↵", ACC, 1.6f)
        bindKey(v, { enter() }, null)
        return v
    }

    private fun dotKey(): View {
        val v = keyView(".", FN, 1.4f)
        bindKey(v, { typeRaw(".") }, if (mode == 0) punct else null)
        return v
    }

    private fun charKey(tok: String): View {
        val isNew = tok.endsWith("*")
        val c = if (isNew) tok.dropLast(1) else tok
        var alts: List<String>? = null
        if (mode == 0 && c == "g") alts = listOf("ğ")
        if (mode == 0 && c == "ç") alts = listOf("c")
        if (mode == 1 && c.length == 1 && c[0] in '0'..'9') {
            val d = c[0] - '0'
            alts = listOf(sub[d].toString(), sup[d].toString())
        }
        if (c == "™") alts = listOf("ᵁᶻ", "ᴺ¹")
        if (mode != 0) {
            val m = altsMap[c]
            if (m != null) alts = m
        }
        val label = if (shift && mode == 0) c.uppercase() else c
        val v = keyView(label, if (isNew) NEWK else NORMAL, 1f)
        bindKey(v, { commit(c) }, alts)
        return v
    }

    fun commit(s: String) {
        val out = if (shift) s.uppercase() else s
        if (isSeparator(s)) onSeparator()
        currentInputConnection?.commitText(out, 1)
        if (shift) {
            shift = false
            requestRender()
        }
        scheduleUpdate()
    }

    fun typeRaw(s: String) {
        onSeparator()
        currentInputConnection?.commitText(s, 1)
        scheduleUpdate()
    }

    fun enter() {
        onSeparator()
        val ic = currentInputConnection ?: return
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnter = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        scheduleUpdate()
    }
}
