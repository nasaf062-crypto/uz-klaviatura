package uz.klaviatura

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
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

class Stat(var c: Int, var t: Long)

class ClipItem(val text: String, val t: Long)

class UzKeyboardService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var mode = 0
    private var shift = false
    private var smart = true

    private val uni = HashMap<String, Stat>()
    private val bi = HashMap<String, Stat>()
    private var dirty = false
    private val saver = Runnable { saveDict() }
    private val upd = Runnable {
        refreshShift()
        updateSuggestions()
    }
    private val sugViews = arrayOfNulls<TextView>(3)

    private val clips = ArrayList<ClipItem>()
    private var clipMgr: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { grabClip() }
    private val ttl = 2L * 60L * 60L * 1000L

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

    // ---------- lug'at (so'z takliflari) ----------

    private fun loadDict() {
        val u = prefs.getString("uni", "") ?: ""
        for (line in u.split("\n")) {
            val p = line.split("\t")
            if (p.size == 3) {
                val c = p[1].toIntOrNull()
                val t = p[2].toLongOrNull()
                if (c != null && t != null) uni[p[0]] = Stat(c, t)
            }
        }
        val b = prefs.getString("bi", "") ?: ""
        for (line in b.split("\n")) {
            val p = line.split("\t")
            if (p.size == 4) {
                val c = p[2].toIntOrNull()
                val t = p[3].toLongOrNull()
                if (c != null && t != null) bi[p[0] + "\t" + p[1]] = Stat(c, t)
            }
        }
    }

    private fun saveDict() {
        if (!dirty) return
        dirty = false
        if (uni.size > 3000) {
            val keep = uni.entries
                .sortedWith(compareByDescending<Map.Entry<String, Stat>> { it.value.c }.thenByDescending { it.value.t })
                .take(2500).map { it.key }.toHashSet()
            uni.keys.retainAll(keep)
        }
        if (bi.size > 4000) {
            val keep = bi.entries
                .sortedWith(compareByDescending<Map.Entry<String, Stat>> { it.value.c }.thenByDescending { it.value.t })
                .take(3000).map { it.key }.toHashSet()
            bi.keys.retainAll(keep)
        }
        val sb = StringBuilder()
        for ((k, s) in uni) sb.append(k).append('\t').append(s.c).append('\t').append(s.t).append('\n')
        val sb2 = StringBuilder()
        for ((k, s) in bi) sb2.append(k).append('\t').append(s.c).append('\t').append(s.t).append('\n')
        prefs.edit().putString("uni", sb.toString()).putString("bi", sb2.toString()).apply()
    }

    private fun learn(word: String, prev: String) {
        if (word.length < 2 || word.length > 24) return
        val now = System.currentTimeMillis()
        val s = uni[word]
        if (s == null) uni[word] = Stat(1, now) else {
            s.c += 1
            s.t = now
        }
        if (prev.length >= 2) {
            val k = prev + "\t" + word
            val b = bi[k]
            if (b == null) bi[k] = Stat(1, now) else {
                b.c += 1
                b.t = now
            }
        }
        dirty = true
        handler.removeCallbacks(saver)
        handler.postDelayed(saver, 8000)
    }

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == 'ʼ' || c == '\'' || c == '’'

    private fun trailingWord(s: String): String {
        var i = s.length
        while (i > 0 && isWordChar(s[i - 1])) i--
        return s.substring(i).trimStart('\'', 'ʼ', '’')
    }

    private fun isSeparator(s: String): Boolean =
        s.isNotEmpty() && !isWordChar(s[0]) && !s[0].isDigit()

    private fun onSeparator() {
        if (!smart) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: return
        val w = trailingWord(before)
        if (w.length < 2) return
        val rest = before.substring(0, before.length - w.length)
        val prev = if (rest.endsWith(" ")) trailingWord(rest.trimEnd(' ')).lowercase() else ""
        learn(w.lowercase(), prev)
    }

    private fun styled(w: String, prefix: String): String {
        val cap = (prefix.isNotEmpty() && prefix[0].isUpperCase()) || (prefix.isEmpty() && shift)
        return if (cap && w.isNotEmpty()) w.substring(0, 1).uppercase() + w.substring(1) else w
    }

    private fun computeSuggestions(): List<String> {
        if (!smart) return emptyList()
        val ic = currentInputConnection ?: return emptyList()
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: return emptyList()
        val prefix = trailingWord(before)
        val out = ArrayList<String>()
        val order = compareByDescending<Map.Entry<String, Stat>> { it.value.c }.thenByDescending { it.value.t }
        if (prefix.isNotEmpty()) {
            val lp = prefix.lowercase()
            val list = uni.entries.filter { it.key.startsWith(lp) && it.key != lp }.sortedWith(order)
            for (e in list.take(3)) out.add(e.key)
        } else {
            val rest = before.trimEnd(' ')
            val prev = if (rest.length < before.length) trailingWord(rest).lowercase() else ""
            if (prev.length >= 2) {
                val list = bi.entries.filter { it.key.startsWith(prev + "\t") }.sortedWith(order)
                for (e in list.take(3)) out.add(e.key.substring(prev.length + 1))
            }
            val atStart = before.isBlank() || before.endsWith("\n") ||
                (rest.length < before.length && rest.isNotEmpty() && rest.last() in ".!?")
            if (out.isEmpty() && atStart) {
                val list = uni.entries.sortedWith(order)
                for (e in list.take(3)) out.add(e.key)
            }
        }
        return out.map { styled(it, prefix) }
    }

    private fun updateSuggestions() {
        val list = try {
            computeSuggestions()
        } catch (e: Exception) {
            emptyList()
        }
        for (i in 0 until 3) {
            val tv = sugViews[i] ?: continue
            tv.text = if (i < list.size) list[i] else ""
        }
    }

    private fun pickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: ""
        val prefix = trailingWord(before)
        val restBefore = before.substring(0, before.length - prefix.length)
        ic.beginBatchEdit()
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText(word + " ", 1)
        ic.endBatchEdit()
        val prev = trailingWord(restBefore.trimEnd(' ')).lowercase()
        learn(word.lowercase(), prev)
        if (shift) {
            shift = false
            requestRender()
        }
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        handler.removeCallbacks(upd)
        handler.postDelayed(upd, 25)
    }

    // ---------- avtomatik katta harf ----------

    private fun wantCaps(): Boolean {
        if (!smart) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(5, 0)?.toString() ?: return false
        if (before.isEmpty()) return true
        if (before.endsWith("\n")) return true
        val t = before.trimEnd(' ')
        if (t.isEmpty()) return before.length < 5
        if (t.length == before.length) return false
        return t.last() in ".!?"
    }

    private fun refreshShift() {
        val want = mode == 0 && wantCaps()
        if (want != shift) {
            shift = want
            requestRender()
        }
    }

    // ---------- bufer ----------

    private fun prune() {
        val now = System.currentTimeMillis()
        clips.removeAll { now - it.t > ttl }
    }

    private fun grabClip() {
        try {
            val cm = clipMgr ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            if (Build.VERSION.SDK_INT >= 26) {
                val ex = clip.description.extras
                if (ex != null && ex.getBoolean("android.content.extra.IS_SENSITIVE", false)) return
            }
            val txt = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (txt.isBlank()) return
            prune()
            clips.removeAll { it.text == txt }
            clips.add(0, ClipItem(txt, System.currentTimeMillis()))
            while (clips.size > 8) clips.removeAt(clips.size - 1)
        } catch (e: Exception) {
        }
    }

    // ---------- chizish ----------

    private fun requestRender() {
        handler.post { render() }
    }

    private fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun render() {
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
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            tv.layoutParams = lp
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

    private fun roundBg(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun keyBg(color: Int): InsetDrawable =
        InsetDrawable(roundBg(color, 8), dp(2), dp(3), dp(2), dp(3))

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

    // ---------- matn kiritish ----------

    private fun commit(s: String) {
        val out = if (
