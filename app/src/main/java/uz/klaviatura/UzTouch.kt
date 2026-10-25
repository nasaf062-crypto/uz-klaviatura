package uz.klaviatura

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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

fun UzKeyboardService.wipeAll() {
    val ic = currentInputConnection ?: return
    val b = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
    val a = ic.getTextAfterCursor(100000, 0)?.length ?: 0
    ic.deleteSurroundingText(b, a)
}

fun UzKeyboardService.openPopup(anchor: View, alts: List<String>): PopupState {
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
            tv.setTextColor(android.graphics.Color.WHITE)
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

fun UzKeyboardService.highlight(st: PopupState, sel: Int) {
    for (i in st.items.indices) {
        st.items[i].background = if (i == sel) roundBg(cAcc, 24) else null
    }
}

fun UzKeyboardService.hit(st: PopupState, wx: Float, wyIn: Float): Int {
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
fun UzKeyboardService.bindKey(v: TextView, tap: () -> Unit, alts: List<String>?) {
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

fun UzKeyboardService.bsKey(): View {
    val v = keyView("⌫", 1, 1.4f)
    bindBackspace(v)
    return v
}

@SuppressLint("ClickableViewAccessibility")
fun UzKeyboardService.bindBackspace(v: TextView) {
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
        v.background = keyBg(cAcc)
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
                v.background = keyBg(cFn)
                scheduleUpdate()
                true
            }
            else -> true
        }
    }
}
