package uz.klaviatura

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

class PopupState(
    val win: PopupWindow,
    val items: List<TextView>,
    val ax: Int,
    val ay: Int,
    val x0: Int,
    val y0: Int,
    val h: Int,
    val cols: Int,
    val itemW: Int,
    val itemH: Int,
    val pad: Int
)

class UzKeyboardService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var mode = 0
    private var shift = false

    private val cBg = Color.parseColor("#1B1B1B")
    private val cKey = Color.parseColor("#3A3A3A")
    private val cFn = Color.parseColor("#505050")
    private val cNew = Color.parseColor("#1F5A3D")
    private val cAcc = Color.parseColor("#4C8DF6")
    private val cPop = Color.parseColor("#3D3D3D")

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(3), dp(4), dp(3), dp(10))
        render()
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting && ::root.isInitialized) {
            mode = 0
            shift = false
            render()
        }
    }

    private fun requestRender() {
        handler.post { render() }
    }

    private fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun render() {
        root.removeAllViews()
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

    private fun buildRow(tokens: List<String>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (t in tokens) row.addView(buildKey(t))
        return row
    }

    private fun buildKey(tok: String): View = when (tok) {
        "GAP" -> spacer(1.4f)
        "SH" -> shiftKey()
        "BS" -> bsKey()
        "NUM", "NUMB" -> fnKey("?123", 1.4f) { mode = 1; shift = false; requestRender() }
        "ABC" -> fnKey("ABC", 1.4f) { mode = 0; requestRender() }
        "SYMB" -> fnKey("=\\<", 1.4f) { mode = 2; requestRender() }
        "SP" -> spaceKey()
        "ENT" -> enterKey()
        "DOT" -> dotKey()
        else -> charKey(tok)
    }

    private fun roundBg(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun keyView(label: String, kind: Int, weight: Float): TextView {
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
        t.background = roundBg(color, 8)
        val lp = LinearLayout.LayoutParams(0, dp(52), weight)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        t.layoutParams = lp
        return t
    }

    private fun spacer(weight: Float): View {
        val v = View(this)
        val lp = LinearLayout.LayoutParams(0, dp(52), weight)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        v.layoutParams = lp
        return v
    }

    private fun fnKey(label: String, w: Float, tap: () -> Unit): View {
        val v = keyView(label, FN, w)
        attach(v, tap, null)
        return v
    }

    private fun shiftKey(): View {
        val v = keyView("⇧", if (shift) ACC else FN, 1.4f)
        attach(v, { shift = !shift; requestRender() }, null)
        return v
    }

    private fun spaceKey(): View {
        val v = keyView("probel", NORMAL, 5f)
        v.textSize = 14f
        attach(v, { typeRaw(" ") }, null)
        return v
    }

    private fun enterKey(): View {
        val v = keyView("↵", ACC, 1.6f)
        attach(v, { enter() }, null)
        return v
    }

    private fun dotKey(): View {
        val v = keyView(".", FN, 1.4f)
        attach(v, { typeRaw(".") }, if (mode == 0) punct else null)
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
        attach(v, { commit(c) }, alts)
        return v
    }

    private fun commit(s: String) {
        val out = if (shift) s.uppercase() else s
        currentInputConnection?.commitText(out, 1)
        if (shift) {
            shift = false
            requestRender()
        }
    }

    private fun typeRaw(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    private fun enter() {
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
    }

    private fun wipeAll() {
        val ic = currentInputConnection ?: return
        val b = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
        val a = ic.getTextAfterCursor(100000, 0)?.length ?: 0
        ic.deleteSurroundingText(b, a)
    }

    private fun openPopup(anchor: View, alts: List<String>): PopupState {
        val sw = resources.displayMetrics.widthPixels
        val cols = if (alts.size > 8) 8 else alts.size
        val rowsN = (alts.size + cols - 1) / cols
        val itemW = minOf(dp(52), (sw - dp(24)) / cols)
        val itemH = dp(46)
        val pad = dp(6)
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setPadding(pad, pad, pad, pad)
        outer.background = roundBg(cPop, 14)
        val items = ArrayList<TextView>()
        for (r in 0 until rowsN) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until cols) {
                val i = r * cols + c
                val tv = TextView(this)
                tv.gravity = Gravity.CENTER
                tv.textSize = 22f
                tv.setTextColor(Color.WHITE)
                if (i < alts.size) {
                    tv.text = alts[i]
                    items.add(tv)
                }
                row.addView(tv, LinearLayout.LayoutParams(itemW, itemH))
            }
            outer.addView(row)
        }
        val w = cols * itemW + 2 * pad
        val h = rowsN * itemH + 2 * pad
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x0 = maxOf(dp(4), minOf(loc[0] + anchor.width / 2 - w / 2, sw - w - dp(4)))
        val y0 = loc[1] - h - dp(8)
        val win = PopupWindow(outer, w, h, false)
        win.isTouchable = false
        win.isClippingEnabled = false
        win.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        win.elevation = dp(8).toFloat()
        win.showAtLocation(anchor, Gravity.NO_GRAVITY, x0, y0)
        return PopupState(win, items, loc[0], loc[1], x0, y0, h, cols, itemW, itemH, pad)
    }

    private fun highlight(st: PopupState, sel: Int) {
        for (i in st.items.indices) {
            st.items[i].background = if (i == sel) roundBg(cAcc, 24) else null
        }
    }

    private fun hit(st: PopupState, wx: Float, wyIn: Float): Int {
        val bottom = st.y0 + st.h - st.pad
        val wy = if (wyIn >= bottom) (bottom - 1).toFloat() else wyIn
        for (i in st.items.indices) {
            val col = i % st.cols
            val row = i / st.cols
            val l = st.x0 + st.pad + col * st.itemW
            val t = st.y0 + st.pad + row * st.itemH
            if (wx >= l && wx < l + st.itemW && wy >= t && wy < t + st.itemH) return i
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(v: TextView, tap: () -> Unit, alts: List<String>?) {
        var held = false
        var st: PopupState? = null
        var sel = -1
        var downX = 0f
        var downY = 0f
        val longRun = Runnable {
            held = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val a = alts
            if (a != null) {
                if (a.size == 1) {
                    commit(a[0])
                } else {
                    val s = openPopup(v, a)
                    st = s
                    sel = a.indexOf(if (a.contains(",")) "," else a[0])
                    highlight(s, sel)
                }
            }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    held = false
                    downX = ev.rawX
                    downY = ev.rawY
                    view.alpha = 0.7f
                    tick(view)
                    if (alts == null) tap() else handler.postDelayed(longRun, 350)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = st
                    if (held && s != null) {
                        val dist = Math.hypot(
                            (ev.rawX - downX).toDouble(),
                            (ev.rawY - downY).toDouble()
                        )
                        if (dist > dp(14).toDouble()) {
                            sel = hit(s, s.ax + ev.x, s.ay + ev.y)
                            highlight(s, sel)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRun)
                    view.alpha = 1f
                    val isUp = ev.actionMasked == MotionEvent.ACTION_UP
                    val s = st
                    if (alts != null) {
                        if (!held) {
                            if (isUp) tap()
                        } else if (s != null) {
                            s.win.dismiss()
                            if (isUp && sel >= 0 && sel < alts.size) commit(alts[sel])
                        }
                    }
                    st = null
                    held = false
                    sel = -1
                    true
                }
                else -> true
            }
        }
    }

    private fun bsKey(): View {
        val v = keyView("⌫", FN, 1.4f)
        attachBackspace(v)
        return v
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBackspace(v: TextView) {
        var wiped = false
        val rep = object : Runnable {
            override fun run() {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                handler.postDelayed(this, 60)
            }
        }
        val startRep = Runnable { handler.post(rep) }
        val wipe = Runnable {
            handler.removeCallbacks(rep)
            wiped = true
            v.text = "↑"
            v.background = roundBg(cAcc, 8)
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wiped = false
                    tick(v)
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    handler.postDelayed(startRep, 350)
                    handler.postDelayed(wipe, 1800)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(startRep)
                    handler.removeCallbacks(rep)
                    handler.removeCallbacks(wipe)
                    if (wiped && ev.actionMasked == MotionEvent.ACTION_UP) wipeAll()
                    wiped = false
                    v.text = "⌫"
                    v.background = roundBg(cFn, 8)
                    true
                }
                else -> true
            }
        }
    }
}
