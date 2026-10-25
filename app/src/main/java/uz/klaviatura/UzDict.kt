package uz.klaviatura

import android.os.Build

class Stat(var c: Int, var t: Long)

class ClipItem(val text: String, val t: Long)

fun isWordChar(c: Char): Boolean = c.isLetter() || c == 'ʼ' || c == '\'' || c == '’'

fun trailingWord(s: String): String {
    var i = s.length
    while (i > 0 && isWordChar(s[i - 1])) i--
    return s.substring(i).trimStart('\'', 'ʼ', '’')
}

fun isSeparator(s: String): Boolean =
    s.isNotEmpty() && !isWordChar(s[0]) && !s[0].isDigit()

fun UzKeyboardService.loadDict() {
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

fun UzKeyboardService.saveDict() {
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

fun UzKeyboardService.learn(word: String, prev: String) {
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

fun UzKeyboardService.onSeparator() {
    if (!smart) return
    val ic = currentInputConnection ?: return
    val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: return
    val w = trailingWord(before)
    if (w.length < 2) return
    val rest = before.substring(0, before.length - w.length)
    val prev = if (rest.endsWith(" ")) trailingWord(rest.trimEnd(' ')).lowercase() else ""
    learn(w.lowercase(), prev)
}

fun UzKeyboardService.styled(w: String, prefix: String): String {
    val cap = (prefix.isNotEmpty() && prefix[0].isUpperCase()) || (prefix.isEmpty() && shift)
    return if (cap && w.isNotEmpty()) w.substring(0, 1).uppercase() + w.substring(1) else w
}

fun UzKeyboardService.computeSuggestions(): List<String> {
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

fun UzKeyboardService.updateSuggestions() {
    val list = try {
        computeSuggestions()
    } catch (e: Exception) {
        emptyList<String>()
    }
    for (i in 0 until 3) {
        val tv = sugViews[i] ?: continue
        tv.text = if (i < list.size) list[i] else ""
    }
}

fun UzKeyboardService.pickSuggestion(word: String) {
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

fun UzKeyboardService.scheduleUpdate() {
    handler.removeCallbacks(upd)
    handler.postDelayed(upd, 25)
}

fun UzKeyboardService.wantCaps(): Boolean {
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

fun UzKeyboardService.refreshShift() {
    val want = mode == 0 && wantCaps()
    if (want != shift) {
        shift = want
        requestRender()
    }
}

fun UzKeyboardService.prune() {
    val now = System.currentTimeMillis()
    clips.removeAll { now - it.t > ttl }
}

fun UzKeyboardService.grabClip() {
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
