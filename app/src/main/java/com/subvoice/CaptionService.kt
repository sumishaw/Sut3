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
        if (!State.speechRecent()) return                  // and only while speech is heard: subtitles belong to speech
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
        if (nodes > 3 || text.length > 220) return          // a subtitle is 1-3 short lines; more text is a page, not a subtitle
        if (text.isNotEmpty() && text != last) {
            last = text
            Pipeline.onCaption(applicationContext, text)
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
            return cx >= v.l && cx <= v.r && cy >= v.t + 0.58f * rh && cy <= v.b + 0.03f &&
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
            val capId = sid.contains("caption") || sid.contains("subtitle") || sid == "subs" || sid == "cue_text" || sid == "cues"
            val byPlace = State.capturing && VideoWatch.tracking && VideoWatch.recent()
            val editable = n.isEditable || (n.className?.toString() ?: "").contains("EditText")
            if ((capId || byPlace) && !n.isClickable && !editable && n.isVisibleToUser && inVideo(n, txt)) { out.append(txt).append(' '); nodes++ }
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, dbg)
    }

    override fun onInterrupt() {}
}

fun similar(a: String, b: String): Boolean {
    fun grams(s: String) = s.filter { !it.isWhitespace() }.windowed(2).toSet()
    val x = grams(a); val y = grams(b)
    if (x.isEmpty() || y.isEmpty()) return a == b
    return x.intersect(y).size.toDouble() / x.union(y).size >= 0.85
}

/**
 * Debounces rolling/jittery captions and speaks only NEW text. Thread-safe entry point.
 *  - a caption replaced by a DIFFERENT line before its timer fires is spoken immediately instead of being lost
 *  - every spoken line is remembered for a while: OCR jitter, a two-line caption collapsing back to one line,
 *    or the same line arriving from both the accessibility reader and OCR is NOT spoken again (no loops)
 */
object Pipeline {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var pending = ""
    private var done = true
    private var spoken = ""
    private var run: Runnable? = null

    private class Said(val norm: String, val grams: Set<String>, val t: Long)
    private val history = ArrayList<Said>()
    private const val WINDOW_MS = 25_000L

    fun reset() {
        run?.let { h.removeCallbacks(it) }
        pending = ""; spoken = ""; done = true
        synchronized(history) { history.clear() }
    }

    /** letters, digits and Devanagari vowel signs only (ignores punctuation, spaces, case). */
    private fun norm(s: String): String = s.filter {
        Character.isLetterOrDigit(it) ||
            Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() }
    }.lowercase()

    private fun grams(n: String): Set<String> = if (n.length < 2) setOf(n) else n.windowed(2).toSet()

    /** True when this text (or nearly all of it) was already spoken a moment ago. */
    private fun alreadySpoken(text: String): Boolean {
        val n = norm(text)
        if (n.isEmpty()) return true
        val g = grams(n)
        val now = System.currentTimeMillis()
        synchronized(history) {
            history.removeAll { now - it.t > WINDOW_MS }
            for (s in history) {
                if (s.norm == n) return true
                if (n.length >= 4 && s.norm.contains(n)) return true            // a part of an earlier line
                if (n.length >= 6 && g.count { it in s.grams }.toDouble() / g.size >= 0.8) return true   // jittery re-read
            }
        }
        return false
    }

    private fun remember(text: String) {
        val n = norm(text)
        if (n.isEmpty()) return
        synchronized(history) {
            history.add(Said(n, grams(n), System.currentTimeMillis()))
            while (history.size > 12) history.removeAt(0)
        }
    }

    /** What is new in [cur] compared with the line spoken before (handles rolling 2-line captions). */
    private fun newPart(cur: String): String {
        if (spoken.isEmpty()) return cur
        if (cur.startsWith(spoken)) return cur.removePrefix(spoken)
        if (spoken.contains(cur)) return ""                              // caption shrank back to one of its lines
        if (similar(cur, spoken)) return ""
        for (k in minOf(spoken.length, cur.length - 1) downTo 6) {      // old tail == start of new text
            if (cur.startsWith(spoken.takeLast(k))) return cur.drop(k)
        }
        return cur
    }

    private fun commit(ctx: Context) {
        if (done) return
        done = true
        val cur = pending
        val delta = newPart(cur).trim()
        spoken = cur
        if (delta.length > 1 && !alreadySpoken(delta)) {
            remember(delta)
            State.lastCaption = delta
            Speaker.enqueue(ctx.applicationContext, delta, State.speaker)
        }
    }

    fun onCaption(ctx: Context, text: String) {
        h.post {
            if (text == pending) return@post                 // same text re-read (OCR polls often)
            run?.let { h.removeCallbacks(it) }
            if (!done && pending.isNotEmpty() && !text.startsWith(pending) && !similar(text, pending)) {
                commit(ctx)                                  // a different line replaced it: speak the old one now
            }
            pending = text; done = false
            run = Runnable { commit(ctx) }
            h.postDelayed(run!!, 250)
        }
    }
}
