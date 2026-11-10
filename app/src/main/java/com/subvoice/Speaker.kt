package com.subvoice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.KeyEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
}

object Media {
    fun key(ctx: Context, code: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (_: Exception) {}
    }
}

/**
 * Real-time pipeline: generation thread -> play thread.
 *  - NO line is skipped normally: when lines pile up playback speeds up (up to 1.5x) instead;
 *    only a really long backlog (MAX_BACKLOG lines) drops the oldest ones
 *  - engine "local" = tablet Hindi voice rendered to a file (instant) -> Termux server /convert re-voices it to the video speaker
 *  - stopAll() instantly silences everything (used by Stop)
 */
object Speaker {
    private const val MAX_BACKLOG = 7
    private class Job(val text: String, val spk: String, val epoch: Int)
    private val jobs = LinkedBlockingDeque<Job>()
    private val playQ = LinkedBlockingDeque<File>()
    private val inFlight = AtomicInteger(0)          // lines being generated, waiting or playing
    @Volatile private var appCtx: Context? = null
    @Volatile private var started = false
    @Volatile private var epoch = 0
    @Volatile private var curMp: MediaPlayer? = null
    @Volatile private var curLatch: CountDownLatch? = null

    /**
     * female/male for a speaker profile (spk_f1, spk_f2 = women, spk_m1, spk_m2 = men): the gender is simply the person
     * that was heard; it changes when the video changes from a female to a male voice (or back).
     */
    private fun genderOf(p: Prefs, vid: String?): String {
        if (p.genderMode != "auto") return p.genderMode
        return when {
            vid == null -> State.gender
            vid.startsWith("spk_f") -> "female"
            vid.startsWith("spk_m") -> "male"
            else -> State.gender
        }
    }

    /** 0 for the first person of a gender (spk_f1 / spk_m1), 1 for the second ... : every person gets a different tablet voice. */
    private fun variantOf(spk: String?): Int {
        if (spk == null || !spk.startsWith("spk_")) return 0
        return ((spk.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
    }

    fun serverFor(p: Prefs, engine: String) = when (engine) {
        "chatterbox" -> p.cbUrl; "local", "fast" -> p.localUrl; else -> p.xttsUrl
    }

    private fun finishOne() {
        val c = appCtx ?: return
        if (inFlight.decrementAndGet() <= 0) {
            inFlight.set(0)
            if (Prefs(c).pauseVideo) Media.key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    /** Stop everything right now: queued lines, the line being played, device speech. */
    fun stopAll(ctx: Context) {
        epoch++
        jobs.clear()
        playQ.forEach { it.delete() }; playQ.clear()
        val busy = inFlight.getAndSet(0) > 0
        curLatch?.countDown()
        try { curMp?.stop() } catch (_: Exception) {}
        DeviceTts.stop()
        if (busy && Prefs(ctx).pauseVideo) Media.key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun start() {
        if (started) return
        started = true
        thread(name = "gen", isDaemon = true) {
            while (true) {
                val j = jobs.take()
                val app = appCtx ?: continue
                if (j.epoch != epoch) continue                        // stopped meanwhile
                val p = Prefs(app)
                try {
                    val f = synth(app, p, j.text, j.spk)
                    if (j.epoch != epoch) { f.delete(); continue }
                    while (playQ.size >= MAX_BACKLOG) playQ.pollFirst()?.let { it.delete(); finishOne() }
                    playQ.put(f)
                } catch (e: Exception) {
                    if (j.epoch != epoch) continue
                    State.status = "Server problem (${e.message}) -> device voice"
                    if (p.fallback) DeviceTts.speak(app, j.text, genderOf(p, j.spk.ifEmpty { null }), p.lang, variantOf(j.spk))
                    finishOne()
                }
            }
        }
        thread(name = "player", isDaemon = true) {
            while (true) {
                val f = playQ.take()
                val ep = epoch
                try { if (ep == epoch) play(f, 1.0f + 0.1f * (inFlight.get() - 1).coerceIn(0, 5)) } catch (_: Exception) {}
                f.delete()
                if (ep == epoch) finishOne()
            }
        }
    }

    fun enqueue(ctx: Context, text: String, spk: String = State.speaker) {
        val app = ctx.applicationContext
        appCtx = app
        val p = Prefs(app)
        if (p.engine == "device") { DeviceTts.speak(app, text, genderOf(p, spk.ifEmpty { null }), p.lang, variantOf(spk)); return }
        if (p.pauseVideo && inFlight.get() == 0) Media.key(app, KeyEvent.KEYCODE_MEDIA_PAUSE)
        inFlight.incrementAndGet()
        start()
        while (jobs.size >= MAX_BACKLOG) { if (jobs.pollFirst() != null) finishOne() }   // only a very long backlog drops old lines
        jobs.put(Job(text, spk, epoch))
    }

    /**
     * Reference of the speaker heard when the line appeared. A reference of the OTHER gender is never borrowed
     * (that is what made female lines sound male): until this speaker's own reference exists, null = plain tablet voice.
     */
    private fun pickRef(spk: String): String? =
        if (spk.isNotEmpty() && spk in State.created.keys) spk else null

    /** Reference to clone: the speaker heard in the video, else the manually chosen clip, else null. */
    private fun refFor(ctx: Context, p: Prefs, spk: String): String? = try {
        if (p.autoRef) {
            val pick = pickRef(spk)
            if (pick != null) { ensureAuto(ctx, pick); pick }
            else if (p.voiceId.isNotEmpty()) { ensureVoice(ctx, p); p.voiceId } else null
        } else if (p.voiceId.isNotEmpty()) { ensureVoice(ctx, p); p.voiceId } else null
    } catch (e: Exception) { State.status = "reference upload: ${e.message}"; null }

    /** Tablet Hindi voice -> WAV file (instant, offline) -> Termux /convert makes it sound like the reference speaker. */
    private fun synthLocal(ctx: Context, p: Prefs, text: String, spk: String): File {
        val vid = refFor(ctx, p, spk)
        val g = genderOf(p, if (spk.isNotEmpty()) spk else vid)
        val raw = File(ctx.cacheDir, "d${System.nanoTime()}.wav")
        val t0 = System.currentTimeMillis()
        if (!DeviceTts.synthToFile(ctx, text, g, p.lang, raw, variantOf(spk))) {
            raw.delete(); error("tablet voice failed (is Hindi voice data installed?)")
        }
        val t1 = System.currentTimeMillis()
        if (vid == null) { State.status = "tablet voice, no reference yet (${t1 - t0} ms)"; return raw }
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("voice_id", vid).addFormDataPart("gender", g)
                .addFormDataPart("file", "t.wav", raw.readBytes().toRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder().url("${serverFor(p, "local")}/convert").post(body).build()
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val f = File(ctx.cacheDir, "t${System.nanoTime()}.wav")
                f.writeBytes(r.body!!.bytes())
                raw.delete()
                State.status = "cloned $vid/$g: voice ${t1 - t0} ms + convert ${System.currentTimeMillis() - t1} ms"
                f
            }
        } catch (e: Exception) {
            State.status = "clone server problem (${e.message}) -> plain tablet voice"
            raw
        }
    }

    private fun synth(ctx: Context, p: Prefs, text: String, spk: String): File {
        val engine = p.engine
        if (engine == "local") return synthLocal(ctx, p, text, spk)
        val cloned = engine == "chatterbox"
        var vid = p.voiceId
        if (cloned && p.autoRef) {
            val pick = pickRef(spk)
            if (pick != null) { ensureAuto(ctx, pick); vid = pick }
            else if (vid.isNotEmpty()) ensureVoice(ctx, p)
        } else if (cloned) ensureVoice(ctx, p)
        // "local" with no reference yet -> use the fast built-in voice instead of failing
        val route = if (engine == "local" && vid.isEmpty()) "fast" else engine
        if (route == "chatterbox" && vid.isEmpty()) error("no voice reference yet - play the video with capture running")
        val g = genderOf(p, if (spk.isNotEmpty()) spk else vid)
        val lang = if (engine == "xtts" || engine == "device") p.lang else "hi"
        val json = JSONObject().put("text", text).put("language", lang)
            .put("gender", g).put("voice_id", vid).put("speaker", spk)
        val req = Request.Builder().url("${serverFor(p, engine)}/tts/$route")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        val t0 = System.currentTimeMillis()
        return Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val f = File(ctx.cacheDir, "t${System.nanoTime()}.wav")
            f.writeBytes(r.body!!.bytes())
            State.status = "$route/$spk/$g generated in ${(System.currentTimeMillis() - t0)} ms"
            f
        }
    }

    private fun filePart(id: String, f: File) = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("voice_id", id)
        .addFormDataPart("file", "ref.audio", f.readBytes().toRequestBody("application/octet-stream".toMediaType()))
        .build()

    private fun ensureAuto(ctx: Context, id: String) {
        val up = State.uploaded[id]
        if (up == null || up < (State.created[id] ?: 0L)) uploadAuto(ctx, id)
    }

    /** Sends a voice captured from the video (spk_1, spk_2 ...) to the local server. */
    fun uploadAuto(ctx: Context, id: String) {
        val p = Prefs(ctx)
        val f = File(ctx.filesDir, "$id.wav")
        if (!f.exists() || (p.engine != "local" && p.engine != "chatterbox")) return
        Net.client.newCall(Request.Builder().url(serverFor(p, p.engine) + "/voice")
            .post(filePart(id, f)).build()).execute().use {
            if (!it.isSuccessful) error("voice upload HTTP ${it.code}")
            State.uploaded[id] = System.currentTimeMillis()
        }
    }

    /** Manually chosen reference clip: re-send when the server/URL changed. */
    fun ensureVoice(ctx: Context, p: Prefs) {
        val f = File(ctx.filesDir, "ref.audio")
        if (p.voiceId.isEmpty() || !f.exists() || p.uploadedTo == serverFor(p, p.engine)) return
        Net.client.newCall(Request.Builder().url(serverFor(p, p.engine) + "/voice")
            .post(filePart(p.voiceId, f)).build()).execute().use {
            if (!it.isSuccessful) error("voice upload HTTP ${it.code}")
            p.uploadedTo = serverFor(p, p.engine)
        }
    }

    private fun play(f: File, speed: Float) {
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        curLatch = latch; curMp = mp
        try {
            mp.setDataSource(f.path)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            mp.prepare(); mp.start()
            if (speed > 1.02f) try { mp.playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Exception) {}
            latch.await(60, TimeUnit.SECONDS)
        } finally { curMp = null; try { mp.release() } catch (_: Exception) {} }
    }
}
