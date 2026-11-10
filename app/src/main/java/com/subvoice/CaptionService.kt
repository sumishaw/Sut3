package com.subvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads subtitle text exposed by the video app itself (ids containing "caption"/"subtitle"). No general screen reading. */
class CaptionService : AccessibilityService() {
    private var last = ""
    private var lastDbg = 0L
    private var sw = 0
    private var sh = 0
    private var visited = 0
    private var nodes = 0

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.packageName == packageName) return
        State.pkg = e.packageName?.toString() ?: ""
        State.events++
        if (!Prefs(this).enabled) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!am.isMusicActive) return                      // only while a video/audio is playing
        if (State.capturing && VideoWatch.tracking && !VideoWatch.recent()) return   // screen reader running: only while a video is really on screen
        run {
            val dm = android.util.DisplayMetrics()
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels
        }
        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()
        val dbg: MutableList<String>? = if (now - lastDbg > 1500) { lastDbg = now; mutableListOf() } else null
        val sb = StringBuilder()
        visited = 0; nodes = 0
        collect(root, sb, dbg)
        if (dbg != null) State.debug = dbg.joinToString("\n")
        val text = sb.toString().trim()
        if (nodes > 6 || text.length > 300) return          // a subtitle is a few short lines; much more text is a page, not a subtitle
        if (text.isNotEmpty() && text != last) {
            last = text
            Pipeline.onCaption(applicationContext, text)
        } else if (text.isEmpty() && last.isNotEmpty()) {
            last = ""
            Pipeline.onGone()
        }
    }

    /** A caption node counts only if it is Hindi, centred in the lower part of the playing video (or of the screen if no video info). */
    private fun inVideo(n: AccessibilityNodeInfo, txt: String): Boolean {
        if (devShare(txt) < 0.5) return false
        if (sw <= 0 || sh <= 0) return true
        val b = Rect(); n.getBoundsInScreen(b)
        if (b.isEmpty || b.height() > sh * 0.15f) return false
        val cx = (b.left + b.right) / 2f / sw
        val cy = (b.top + b.bottom) / 2f / sh
        if (State.capturing && VideoWatch.tracking) {
            val v = VideoWatch
            val rw = v.r - v.l; val rh = v.b - v.t
            return cx >= v.l && cx <= v.r && cy >= v.t + 0.45f * rh && cy <= v.b + 0.03f &&
                kotlin.math.abs(cx - (v.l + v.r) / 2f) < 0.25f * rw
        }
        return cy > 0.6f && kotlin.math.abs(cx - 0.5f) < 0.25f
    }

    private fun collect(n: AccessibilityNodeInfo?, out: StringBuilder, dbg: MutableList<String>?) {
        if (n == null || ++visited > 2500) return
        val id = n.viewIdResourceName?.lowercase() ?: ""
        val txt = n.text?.toString()
        if (!txt.isNullOrBlank()) {
            if (dbg != null && dbg.size < 8) dbg.add(id.substringAfterLast('/') + ": " + txt.take(40))
            // Browsers (Chrome etc.) give web text no view id, so while the playing video's position is known, ANY non-clickable
            // Hindi text lying centred in the lower part of that video counts; otherwise the id must say caption/subtitle.
            val sid = id.substringAfterLast('/')
            val capId = sid.contains("caption") || sid.contains("subtitle") || sid.contains("subs") || sid.contains("cue")
            val byPlace = State.capturing && VideoWatch.tracking && VideoWatch.recent()
            val editable = n.isEditable || (n.className?.toString() ?: "").contains("EditText")
            if ((capId || byPlace) && !n.isClickable && !editable && n.isVisibleToUser && inVideo(n, txt)) { out.append(txt).append(' '); nodes++ }
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, dbg)
    }

    override fun onInterrupt() {}
}

/** Letters, digits and Devanagari vowel signs only (ignores punctuation, spaces, case). */
fun normText(s: String): String = s.filter {
    Character.isLetterOrDigit(it) ||
        Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() }
}.lowercase()

/** Similarity 0..1 of two normalised texts (edit distance), tolerant to the small reading mistakes of OCR. */
fun textSim(a: String, b: String): Double {
    if (a == b) return 1.0
    val n = a.length
    val m = b.length
    if (n == 0 || m == 0) return 0.0
    var prev = IntArray(m + 1) { it }
    var cur = IntArray(m + 1)
    for (i in 1..n) {
        cur[0] = i
        for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return 1.0 - prev[m].toDouble() / maxOf(n, m)
}

/** The same caption read twice (OCR jitter, punctuation, one wrong letter)? Very short texts must be equal. */
fun similar(a: String, b: String): Boolean {
    val x = normText(a)
    val y = normText(b)
    if (x.length < 5 || y.length < 5) return x == y
    return textSim(x, y) >= 0.78
}

private fun sameWord(a: String, b: String): Boolean {
    val x = normText(a)
    val y = normText(b)
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    if (x.length < 3 || y.length < 3) return false
    return textSim(x, y) >= 0.7
}

private fun words(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * Debounces rolling/jittery captions and speaks every NEW sentence exactly once.
 *  - while the SAME caption stays on screen it is never spoken again, however often (or how differently) OCR reads it
 *  - a sentence that comes back AFTER other sentences, or after the caption was gone for a moment, is spoken normally
 *  - a caption replaced by a DIFFERENT line before its timer fires is spoken immediately instead of being lost
 */
object Pipeline {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var pending = ""
    private var done = true
    private var spoken = ""               // full text of the caption as it was when last committed
    private var run: Runnable? = null
    private var arrivedAt = 0L            // when the current caption first appeared
    private var framesAtArrival = 0       // voiced frames the voice tracker had heard by then

    private var lastDelta = ""            // the sentence spoken last
    private var lastAt = 0L
    private var lastGone = false          // the caption disappeared since that sentence was spoken
    private var blankSince = 0L           // screen reader sees no subtitle since ...

    fun reset() {
        run?.let { h.removeCallbacks(it) }
        pending = ""; spoken = ""; done = true
        lastDelta = ""; lastAt = 0L; lastGone = false; blankSince = 0L
    }

    /** The subtitle reader found NO subtitle on screen right now. */
    fun onGone() {
        h.post { if (blankSince == 0L) blankSince = System.currentTimeMillis() }
    }

    /** The same sentence as the one spoken last, with no other sentence in between? (a different sentence between = fine) */
    private fun repeated(delta: String): Boolean {
        val n = normText(delta)
        if (n.isEmpty()) return true
        val now = System.currentTimeMillis()
        if (lastDelta.isEmpty() || now - lastAt > 40_000) return false
        if (lastGone && now - lastAt >= 2500) return false          // it went away and this is a genuine new showing
        if (similar(delta, lastDelta)) return true
        if (n.length >= 6 && normText(lastDelta).contains(n)) return true
        return false
    }

    /** What is new in [cur] compared with the caption committed before (handles rolling 2-line captions and OCR jitter). */
    private fun newPart(cur: String): String {
        if (spoken.isEmpty()) return cur
        if (similar(cur, spoken)) return ""
        val sw = words(spoken)
        val cw = words(cur)
        if (sw.isEmpty() || cw.isEmpty()) return cur
        // only a part of what was spoken is on screen (caption shrank / one line of a two-line caption)
        if (normText(cur).length >= 6 && cw.size <= sw.size) {
            for (st in 0..(sw.size - cw.size)) {
                var ok = true
                for (i in cw.indices) if (!sameWord(sw[st + i], cw[i])) { ok = false; break }
                if (ok) return ""
            }
        }
        // rolling caption: the end of the old text is the start of the new text -> only the rest is new
        for (k in minOf(sw.size, cw.size - 1) downTo 1) {
            var ok = true
            for (i in 0 until k) if (!sameWord(sw[sw.size - k + i], cw[i])) { ok = false; break }
            if (ok) {
                var chars = 0
                for (i in 0 until k) chars += normText(cw[i]).length
                if (chars >= 6) return cw.drop(k).joinToString(" ")
            }
        }
        return cur
    }

    private fun commit(ctx: Context, force: Boolean = false) {
        if (done) return
        // A subtitle often appears a moment BEFORE its speaker starts talking. Give the voice tracker a short time (max 0.7 s)
        // to hear the voice of THIS line (>= 5 voiced frames), so a man after a woman (or a new person) gets the right voice.
        // If no new voice is heard in that time, the last voice simply stays.
        val now = System.currentTimeMillis()
        if (!force && State.capturing && now >= State.muteUntil &&
            State.voicedFrames - framesAtArrival < 5 && now - arrivedAt < 700) {
            val r = Runnable { commit(ctx) }
            run = r
            h.postDelayed(r, 60)
            return
        }
        done = true
        val cur = pending
        val delta = newPart(cur).trim()
        spoken = cur
        if (delta.length > 1 && !repeated(delta)) {
            lastDelta = delta; lastAt = System.currentTimeMillis(); lastGone = false
            State.lastCaption = delta
            Speaker.enqueue(ctx.applicationContext, delta, State.speaker)
        }
    }

    fun onCaption(ctx: Context, text: String) {
        h.post {
            val now = System.currentTimeMillis()
            if (blankSince != 0L && now - blankSince >= 800 && done) {
                pending = ""; spoken = ""; lastGone = true            // the caption really went away: a fresh start
            }
            blankSince = 0L
            if (text == pending) return@post                 // same text re-read (OCR polls often)
            run?.let { h.removeCallbacks(it) }
            if (!done && pending.isNotEmpty() && !text.startsWith(pending) && !similar(text, pending)) {
                commit(ctx, true)                            // a different line replaced it: speak the old one now
            }
            if (done || pending.isEmpty() || !(text.startsWith(pending) || similar(text, pending))) {
                arrivedAt = System.currentTimeMillis(); framesAtArrival = State.voicedFrames    // a NEW caption
            }
            pending = text; done = false
            run = Runnable { commit(ctx) }
            h.postDelayed(run!!, 250)
        }
    }
}
