package com.subvoice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fully offline speech using the tablet's own TTS engine (Google Speech Services Hindi voices).
 *
 * Voice numbering: Google lists the installed Hindi voices as "Voice I, II, III, IV" in system settings.
 * The Android API does not expose those display names, so they are matched by order of the installed voices.
 * Preference: FEMALE = Voice II first, then Voice I;  MALE = Voice IV first, then Voice III.
 * Because that numbering is only a guess, every voice is MEASURED once (its real pitch is analysed) and a voice is
 * never used for the wrong gender, whatever its name or position is.
 */
object DeviceTts {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val lock = Any()
    private val pendingCb = ArrayList<() -> Unit>()

    private class Waiter { val latch = CountDownLatch(1); @Volatile var ok = false }
    private val waiters = ConcurrentHashMap<String, Waiter>()
    private val speaking = java.util.concurrent.atomic.AtomicInteger(0)
    private fun endSpeak(id: String?) {
        if (id?.startsWith("u") != true) return
        if (speaking.decrementAndGet() <= 0) { speaking.set(0); State.muteUntil = System.currentTimeMillis() + 150 }
    }

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
    private val FEMALE_PREF = listOf(1, 0)  // Voice II first, then Voice I
    private val MALE_PREF = listOf(3, 2)    // Voice IV first, then Voice III
    private val vf0 = ConcurrentHashMap<String, Float>()     // measured pitch (Hz) of each installed voice
    private val calLock = Any()
    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context, onReady: (() -> Unit)? = null) {
        appCtx = ctx.applicationContext
        synchronized(pendingCb) {
            if (ready) { onReady?.invoke(); return }
            if (onReady != null) pendingCb.add(onReady)
            if (tts != null) return                       // init already running: callback will be called when ready
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { if (id?.startsWith("u") == true) { speaking.incrementAndGet(); State.muteUntil = Long.MAX_VALUE } }
                        override fun onDone(id: String?) { endSpeak(id); waiters.remove(id)?.let { it.ok = true; it.latch.countDown() } }
                        @Deprecated("old callback") override fun onError(id: String?) { endSpeak(id); waiters.remove(id)?.latch?.countDown() }
                        override fun onError(id: String?, code: Int) { endSpeak(id); waiters.remove(id)?.latch?.countDown() }
                    })
                    val cbs = synchronized(pendingCb) { val c = ArrayList(pendingCb); pendingCb.clear(); c }
                    cbs.forEach { try { it() } catch (_: Exception) {} }
                    calibrate(ctx.applicationContext, null)          // measure the real gender of every voice (once)
                } else {
                    State.status = "Device TTS init failed"
                    synchronized(pendingCb) { pendingCb.clear() }
                    tts = null
                }
            }
        }
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {}; speaking.set(0); State.muteUntil = 0L }

    /** Installed, offline voices of a language in a stable order (this order = Voice I, II, III, IV ...). */
    fun installedVoices(lang: String): List<Voice> {
        if (!ready) return emptyList()
        val all = (tts?.voices ?: emptySet()).filter {
            it.locale.language == lang && !it.name.endsWith("-language") &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val local = all.filter { !it.isNetworkConnectionRequired }
        return (if (local.isNotEmpty()) local else all).sortedBy { it.name }
    }

    /** "female" / "male" from the measured pitch of the voice, or null if it has not been measured yet. */
    fun genderOfVoice(name: String): String? {
        val f = vf0[name] ?: appCtx?.getSharedPreferences("subvoice", Context.MODE_PRIVATE)
            ?.getFloat("vf0_$name", 0f)?.takeIf { it > 0f }?.also { vf0[name] = it }
        return f?.let { if (it > 165f) "female" else "male" }
    }

    fun label(index: Int, v: Voice): String {
        val g = genderOfVoice(v.name)?.let { "  [$it ~${vf0[v.name]?.toInt()} Hz]" } ?: ""
        return "Voice ${ROMAN.getOrElse(index) { (index + 1).toString() }}  (${v.name})$g"
    }

    /** Voices usable for a gender, best first: saved choice, then Voice II / I (female) or Voice IV / III (male), then any measured one. */
    private fun candidates(p: Prefs, gender: String): List<String> {
        val list = installedVoices("hi")
        if (list.isEmpty()) return emptyList()
        fun ok(n: String) = genderOfVoice(n).let { it == null || it == gender }
        val saved = if (gender == "male") p.devMale else p.devFemale
        val out = ArrayList<String>()
        list.firstOrNull { it.name == saved && ok(it.name) }?.let { out.add(it.name) }
        val pref = if (gender == "male") MALE_PREF else FEMALE_PREF
        for (i in pref) list.getOrNull(i)?.let { if (ok(it.name)) out.add(it.name) }
        for (v in list) if (genderOfVoice(v.name) == gender) out.add(v.name)
        return out.distinct()
    }

    /**
     * The voice that will really be used for a gender. [variant] = which person of that gender (0 = first, 1 = second ...):
     * the first person gets the first choice (Voice II / IV), the second the next one (Voice I / III), so two women (or two
     * men) of the video never sound the same.
     */
    fun resolveName(p: Prefs, gender: String, variant: Int = 0): String? {
        val list = installedVoices("hi")
        if (list.isEmpty()) return null
        val c = candidates(p, gender)
        if (c.isNotEmpty()) return c[variant % c.size]
        val pref = if (gender == "male") MALE_PREF else FEMALE_PREF
        return list[minOf(pref[0], list.size - 1)].name
    }

    /** More persons than voices: the extra ones get a slightly lower / higher version of a voice. */
    private fun pitchFor(p: Prefs, gender: String, variant: Int): Float {
        val n = candidates(p, gender).size
        if (n == 0 || variant < n) return 1f
        return if ((variant - n) % 2 == 0) 0.92f else 1.08f
    }

    /** Measures the pitch of every installed Hindi voice once (cached), so gender never depends on guessing. */
    fun calibrate(ctx: Context, done: (() -> Unit)?) {
        Thread {
            synchronized(calLock) {
                try {
                    val t = tts
                    if (ready && t != null) {
                        for (v in installedVoices("hi")) {
                            if (genderOfVoice(v.name) != null) continue
                            val f = File(ctx.cacheDir, "cal${System.nanoTime()}.wav")
                            try {
                                if (renderWithVoice(t, v.name, "नमस्ते, आज मौसम बहुत अच्छा है, आप सब कैसे हैं।", f)) {
                                    val hz = wavF0(f)
                                    if (hz > 0f) {
                                        vf0[v.name] = hz
                                        ctx.getSharedPreferences("subvoice", Context.MODE_PRIVATE).edit().putFloat("vf0_${v.name}", hz).apply()
                                    }
                                }
                            } finally { f.delete() }
                        }
                    }
                } catch (_: Exception) {}
            }
            try { done?.invoke() } catch (_: Exception) {}
        }.start()
    }

    private fun renderWithVoice(t: TextToSpeech, name: String, text: String, out: File): Boolean {
        val id = "c${System.nanoTime()}"
        val w = Waiter()
        synchronized(lock) {
            t.setLanguage(Locale.forLanguageTag("hi-IN"))
            val v = t.voices?.firstOrNull { it.name == name } ?: return false
            t.setVoice(v); t.setPitch(1f); t.setSpeechRate(1f)
            waiters[id] = w
            if (t.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) { waiters.remove(id); return false }
        }
        w.latch.await(10, TimeUnit.SECONDS)
        waiters.remove(id)
        return w.ok && out.exists() && out.length() > 100
    }

    /** Median pitch (Hz) of the voiced parts of a PCM16 WAV file; 0 if none found. */
    private fun wavF0(f: File): Float {
        val b = f.readBytes()
        if (b.size < 64) return 0f
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sr = bb.getInt(24)
        var pos = 12; var off = -1; var len = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII); val l = bb.getInt(pos + 4)
            if (id == "data") { off = pos + 8; len = if (l < 0 || l > b.size - off) b.size - off else l; break }
            if (l < 0) break
            pos += 8 + l
        }
        if (off < 0 || sr < 8000) return 0f
        val n = len / 2
        val x = FloatArray(n) { bb.getShort(off + it * 2).toFloat() }
        val frame = (sr * 0.045).toInt(); val hop = frame / 2
        val lo = sr / 350; val hi = sr / 75
        if (n < frame * 3) return 0f
        val starts = (0..(n - frame) step hop).toList()
        val rms = starts.map { s -> var e = 0.0; for (i in s until s + frame) e += x[i] * x[i]; Math.sqrt(e / frame) }
        val peak = rms.maxOrNull() ?: return 0f
        val out = ArrayList<Float>()
        for ((k, s) in starts.withIndex()) {
            if (rms[k] < 0.2 * peak) continue
            var e = 0.0; for (i in s until s + frame) e += x[i].toDouble() * x[i]
            val r = DoubleArray(hi + 2)
            var best = 0.0
            for (lag in lo..hi) {
                var c = 0.0
                for (i in s until s + frame - lag) c += x[i].toDouble() * x[i + lag]
                r[lag] = c / e
                if (r[lag] > best) best = r[lag]
            }
            if (best < 0.5) continue
            for (lag in lo + 1 until hi) {
                if (r[lag] >= 0.88 * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) { out.add(sr.toFloat() / lag); break }
            }
        }
        if (out.size < 4) return 0f
        out.sort()
        return out[out.size / 2]
    }

    /** Must be called inside synchronized(lock). Sets language, then the exact voice, pitch and rate. */
    private fun applyVoice(t: TextToSpeech, p: Prefs, gender: String, lang: String, rate: Float, variant: Int = 0): Boolean {
        val tag = if (lang == "hi") "hi-IN" else lang
        val r = t.setLanguage(Locale.forLanguageTag(tag))      // must come before setVoice
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            State.status = "Install '$tag' voice data (button in app)"; return false
        }
        if (lang == "hi") {
            val name = resolveName(p, gender, variant)
            val v = name?.let { n -> t.voices?.firstOrNull { it.name == n } }
            if (v != null && t.setVoice(v) != TextToSpeech.SUCCESS) State.status = "could not select ${v.name}"
        }
        t.setPitch(if (lang == "hi") pitchFor(p, gender, variant) else 1f)    // 1.0 unless there are more persons than voices
        t.setSpeechRate(rate)
        return true
    }

    fun speak(ctx: Context, text: String, gender: String, lang: String, variant: Int = 0) {
        if (!ready) { init(ctx) { speak(ctx, text, gender, lang, variant) }; return }
        val t = tts ?: return
        val p = Prefs(ctx)
        synchronized(lock) {
            if (!applyVoice(t, p, gender, lang, 1.1f, variant)) return
            State.muteUntil = System.currentTimeMillis() + 1500L   // safety; exact end is set by the TTS callbacks
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
        }
        State.status = "spoke (device/$gender)"
    }

    /**
     * Renders the tablet's own Hindi voice into a WAV file (no sound is played) and waits for it.
     * This is near-instant and is the base that the Termux server turns into the cloned voice.
     */
    fun synthToFile(ctx: Context, text: String, gender: String, lang: String, out: File, variant: Int = 0): Boolean {
        if (!ready) {
            val l = CountDownLatch(1)
            init(ctx) { l.countDown() }
            l.await(4, TimeUnit.SECONDS)
        }
        val t = tts ?: return false
        if (!ready) return false
        val id = "f${System.nanoTime()}"
        val w = Waiter()
        synchronized(lock) {
            if (!applyVoice(t, Prefs(ctx), gender, lang, 1.15f, variant)) return false
            waiters[id] = w
            if (t.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) { waiters.remove(id); return false }
        }
        w.latch.await(8, TimeUnit.SECONDS)
        waiters.remove(id)
        return w.ok && out.exists() && out.length() > 100
    }

    /** Says a short sample with one specific voice (used when picking a voice in the app). */
    fun preview(ctx: Context, voiceName: String) {
        val t = tts ?: return
        if (!ready) return
        synchronized(lock) {
            t.setLanguage(Locale.forLanguageTag("hi-IN"))
            t.voices?.firstOrNull { it.name == voiceName }?.let { t.setVoice(it) }
            t.setPitch(1f); t.setSpeechRate(1.0f)
            t.speak("नमस्ते, यह मेरी आवाज़ है।", TextToSpeech.QUEUE_FLUSH, null, "pv${System.nanoTime()}")
        }
    }
}
