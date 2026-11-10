package com.subvoice

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap 
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Where the playing video is on screen (fractions 0..1), shared with the accessibility caption reader. */
object VideoWatch {
    @Volatile var l = 0f; @Volatile var t = 0f; @Volatile var r = 1f; @Volatile var b = 1f
    @Volatile var lastActive = 0L
    @Volatile var tracking = false          // true while the screen reader is running (so a video rectangle can be known)
    fun set(v: FloatArray) { l = v[0]; t = v[1]; r = v[2]; b = v[3]; lastActive = System.currentTimeMillis() }
    fun off() { lastActive = 0L }
    fun recent() = System.currentTimeMillis() - lastActive < 6000
}

/** Share (0..1) of the non-space characters that are Devanagari. */
fun devShare(s: String): Double {
    val x = s.filter { !it.isWhitespace() }
    if (x.isEmpty()) return 0.0
    return x.count { it.code in 0x0900..0x097F }.toDouble() / x.length
}

/**
 * One screen-capture permission powers:
 *  1. playback-audio capture -> pitch-based speaker gender + SEPARATE female/male clone references (8 s each)
 *  2. screen OCR (on-device ML Kit, Devanagari) -> centred subtitle lines only, while a video is playing
 */
class AudioCaptureService : Service() {
    companion object { const val RING = 16000 * 8 }
    private var proj: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    private var recognizer: TextRecognizer? = null
    private var audioT: Thread? = null
    private var ocrT: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null || i.action == "stop") { fullStop(); return START_NOT_STICKY }
        cleanup()                                   // a second Start must never leave the first capture running
        val ch = "cap"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "SubVoice capture", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 7,
            Intent(this, AudioCaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ch).setContentTitle("SubVoice is reading subtitles and audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(null, "STOP", stopPi).build()).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data") ?: run { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {}, null)
        }
        running = true; State.capturing = true
        State.audioFrames = 0; State.audioLoud = 0
        startAudio()
        if (Prefs(this).ocr) startOcr()
        State.status = "capture running"
        return START_NOT_STICKY
    }

    // ---------------- audio: gender + per-gender clone references ----------------
    private fun startAudio() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .excludeUid(Process.myUid())
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        rec = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(16000 * 2).build()
        rec!!.startRecording()
        audioT = thread(name = "audio") { audioLoop() }
    }

    /**
     * Tracks WHO is speaking (see [VoiceTracker]): gender from the pitch (robust YIN pitch, median of the last 8 voiced
     * frames), and WHICH PERSON of that gender from pitch + timbre. Persons are spk_f1, spk_f2 ... (women) and
     * spk_m1, spk_m2 ... (men). Each person keeps a rolling buffer of their own latest 8 s of audio, saved
     * (re-saved every >=20 s) as that person's clone reference, so every person of the video gets their own cloned voice.
     */
    private fun audioLoop() {
        val buf = ShortArray(512)               // 32 ms hop
        val win = ShortArray(1024)              // sliding 64 ms analysis window
        val pe = FloatArray(1024)
        val tracker = VoiceTracker()
        var lastVoiced = 0L
        val prefs = Prefs(this)

        while (running) {
            val n = rec?.read(buf, 0, buf.size) ?: break
            if (n < 0) break
            if (n < buf.size) continue
            State.audioFrames++
            val now = System.currentTimeMillis()
            if (now < State.muteUntil) continue            // on-device TTS is speaking
            var e = 0.0
            for (s in buf) e += s.toDouble() * s
            if (sqrt(e / buf.size) < 100) continue         // silence (low threshold: quiet dialogue must still be tracked)
            State.audioLoud++
            System.arraycopy(win, 512, win, 0, 512); System.arraycopy(buf, 0, win, 512, 512)
            pe[0] = win[0].toFloat()
            for (i in 1 until 1024) pe[i] = win[i].toFloat() - 0.8f * win[i - 1].toFloat()   // pre-emphasis: no bass / rumble pitch
            val f = VoiceDsp.yin(pe)
            var outlier = false
            if (f > 0f) {
                lastVoiced = now
                State.lastSpeech = now
                State.voicedFrames++
                val v = tracker.onFrame(now, f.toDouble(), VoiceDsp.mfcc(win))
                State.profileF0["_live"] = f; State.profileF0["_score"] = tracker.lastDist.toFloat()
                if (v != null) {
                    State.speaker = v.id
                    State.gender = if (v.female) "female" else "male"
                    State.profileF0[v.id] = exp(v.lf).toFloat()
                    outlier = abs(ln(f.toDouble()) - v.lf) > 0.3      // a pitch far from this person: not their voice
                }
            }
            val c = tracker.cur ?: continue
            if (!prefs.autoRef) continue
            if (now - lastVoiced > 500 || outlier) continue        // keep the reference clean: speech of this person only
            for (s in buf) { c.ring[c.pos] = s; c.pos = (c.pos + 1) % RING }
            c.filled = minOf(RING, c.filled + buf.size)
            val need = if (c.lastSave == 0L) 16000 * 5 else RING      // first reference after 5 s, then 8 s windows
            if (c.filled >= need && now - c.lastSave > 20_000) { saveRef(c, need); c.lastSave = now }
        }
    }

    private fun saveRef(p: Voice, samples: Int) {
        val total = minOf(samples, p.filled)
        val pcm = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN)
        val start = (p.pos - total + RING) % RING
        for (i in 0 until total) pcm.putShort(p.ring[(start + i) % RING])
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray()); hdr.putInt(36 + pcm.capacity()); hdr.put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray()); hdr.putInt(16); hdr.putShort(1); hdr.putShort(1)
        hdr.putInt(16000); hdr.putInt(32000); hdr.putShort(2); hdr.putShort(16)
        hdr.put("data".toByteArray()); hdr.putInt(pcm.capacity())
        File(filesDir, "${p.id}.wav").outputStream().use { it.write(hdr.array()); it.write(pcm.array()) }
        State.created[p.id] = System.currentTimeMillis()
        State.status = "updated voice reference ${p.id}"
        thread {
            try { Speaker.uploadAuto(applicationContext, p.id) }
            catch (e: Exception) { State.status = "reference upload: ${e.message}" }
        }
    }

    // ---------------- screen OCR (centred subtitle lines only) ----------------
    @Suppress("DEPRECATION")
    @Synchronized
    private fun setupDisplay() {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        // up to 1920 px on the long side: subtitles of a small embedded (browser) player need real detail for OCR
        val sc = minOf(1f, 1920f / maxOf(dm.widthPixels, dm.heightPixels))
        val w = (dm.widthPixels * sc).toInt(); val h = (dm.heightPixels * sc).toInt()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = reader
        if (vd == null) {
            vd = proj!!.createVirtualDisplay("subvoice-ocr", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        } else {
            vd!!.resize(w, h, dm.densityDpi); vd!!.surface = r.surface
        }
        reader = r
        old?.close()
    }

    private fun startOcr() {
        recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        setupDisplay()
        VideoWatch.tracking = true
        ocrT = thread(name = "ocr") { ocrLoop() }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (vd != null) setupDisplay()
    }

    // ---- playing-video detection: the video is the part of the screen whose pixels keep changing ----
    private var prevLum: IntArray? = null
    private var acc = FloatArray(0)
    private val motionHist = ArrayDeque<Boolean>()

    private val rectHist = ArrayList<FloatArray>()

    private fun resetMotion() { prevLum = null; motionHist.clear(); rectHist.clear(); heldRect = null }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val l = maxOf(a[0], b[0]); val t = maxOf(a[1], b[1]); val r = minOf(a[2], b[2]); val bt = minOf(a[3], b[3])
        val inter = maxOf(0f, r - l) * maxOf(0f, bt - t)
        val ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    /**
     * A video is a rectangle that keeps moving AT THE SAME PLACE for a while. A scrolling page, a swipe, a pop-up or an
     * animation moves different areas from moment to moment (or only for a moment) and is therefore never accepted.
     */
    private var heldRect: FloatArray? = null
    private var heldAt = 0L

    private fun stableRect(r: FloatArray?): FloatArray? {
        val now = System.currentTimeMillis()
        if (r != null) {
            rectHist.add(r)
            while (rectHist.size > 6) rectHist.removeAt(0)
            val same = rectHist.count { iou(it, r) >= 0.75f }
            if (rectHist.size >= 4 && same >= 3) { heldRect = r; heldAt = now; return r }
        }
        // a calm scene (little motion for a moment) must not stop the subtitles: keep the video area found a moment ago
        val h = heldRect
        if (h != null && now - heldAt < 6000) return h
        return null
    }

    /**
     * Returns the playing video's rectangle (fractions l,t,r,b of the screen) or null when no video is playing.
     * Moving cells are grouped into connected areas; the biggest one with a video-like shape is the player. Ads, GIFs and
     * other animated bits of a web page are separate areas and are ignored, so a player embedded in a page is found exactly.
     */
    private fun videoRect(frame: Bitmap): FloatArray? {
        val nw = (frame.width / 12).coerceAtLeast(8); val nh = (frame.height / 12).coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(frame, nw, nh, true)
        val px = IntArray(nw * nh); small.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (small !== frame) small.recycle()
        val lum = IntArray(px.size) { val p = px[it]; (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10 }
        val prev = prevLum
        if (prev == null || prev.size != lum.size) { prevLum = lum; acc = FloatArray(lum.size); motionHist.clear(); return null }
        prevLum = lum
        var moving = 0
        val mask = BooleanArray(lum.size)
        for (i in lum.indices) {
            val mv = abs(lum[i] - prev[i]) > 3
            if (mv) moving++
            acc[i] = if (mv) 1f else acc[i] * 0.88f
            mask[i] = acc[i] > 0.3f
        }
        // "playing" = a real share of the screen changes in most recent frames (a static page or one short scroll does not)
        motionHist.addLast(moving >= lum.size * 0.02); while (motionHist.size > 8) motionHist.removeFirst()
        if (!motionHist.last() || motionHist.count { it } < 4) return null

        // connected moving areas (cells up to 2 apart belong together)
        val seen = BooleanArray(lum.size)
        val stack = IntArray(lum.size)
        var bestCnt = 0; var bx0 = 0; var by0 = 0; var bx1 = 0; var by1 = 0; var bestShape = false
        for (start in lum.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var cnt = 0; var x0 = nw; var x1 = 0; var y0 = nh; var y1 = 0
            while (sp > 0) {
                val cell = stack[--sp]; cnt++
                val cx = cell % nw; val cy = cell / nw
                if (cx < x0) x0 = cx; if (cx > x1) x1 = cx; if (cy < y0) y0 = cy; if (cy > y1) y1 = cy
                for (dy in -2..2) for (dx in -2..2) {
                    val ny = cy + dy; val nx = cx + dx
                    if (ny < 0 || ny >= nh || nx < 0 || nx >= nw) continue
                    val ni = ny * nw + nx
                    if (mask[ni] && !seen[ni]) { seen[ni] = true; stack[sp++] = ni }
                }
            }
            val aspect = (x1 - x0 + 1).toFloat() / (y1 - y0 + 1)
            val shape = aspect in 0.4f..3.5f
            // a video-shaped area always beats an odd-shaped one; otherwise the bigger one wins
            if ((shape && !bestShape) || (shape == bestShape && cnt > bestCnt)) {
                bestCnt = cnt; bx0 = x0; bx1 = x1; by0 = y0; by1 = y1; bestShape = shape
            }
        }
        if (bestCnt < lum.size * 0.025) return null
        val bw = bx1 - bx0 + 1; val bh = by1 - by0 + 1
        if (bw < nw * 0.12 || bh < nh * 0.08) return null
        // letterboxed films keep their subtitles in the (static) black bar under the picture: extend a little downwards and sideways
        val l = (bx0 - nw * 0.02f).coerceAtLeast(0f) / nw
        val r = (bx1 + 1 + nw * 0.02f).coerceAtMost(nw.toFloat()) / nw
        val t = by0.toFloat() / nh
        val b = (by1 + 1 + bh * 0.15f).coerceAtMost(nh.toFloat()) / nh
        return floatArrayOf(l, t, r, b)
    }

    private fun ocrLoop() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val rc = recognizer ?: return
        while (running) {
            try {
                val img = reader?.acquireLatestImage()
                // read only while audio is playing and SubVoice's own screen is not in front
                if (img != null && (State.appVisible || !am.isMusicActive)) {
                    img.close(); State.ocrText = "(idle)"; resetMotion(); VideoWatch.off()
                } else if (img != null) {
                    val w = img.width; val h = img.height
                    val pl = img.planes[0]
                    val rowPad = pl.rowStride - pl.pixelStride * w
                    val full = Bitmap.createBitmap(w + rowPad / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(pl.buffer)
                    img.close()
                    val frame = if (full.width == w) full else Bitmap.createBitmap(full, 0, 0, w, h)
                    val vr = stableRect(videoRect(frame))
                    if (vr == null) {
                        State.ocrText = "(no playing video detected - subtitles are read only from a playing video)"
                        VideoWatch.off()
                    } else {
                        VideoWatch.set(vr)
                        val vx = (vr[0] * w).toInt().coerceIn(0, w - 2)
                        val vy = (vr[1] * h).toInt().coerceIn(0, h - 2)
                        val vw = minOf(((vr[2] - vr[0]) * w).toInt(), w - vx)
                        val vh = minOf(((vr[3] - vr[1]) * h).toInt(), h - vy)
                        val top = vy + (vh * 0.45).toInt()              // subtitles sit in the lower part of the VIDEO itself (never the whole screen)
                        val ch = vy + vh - top
                        if (vw >= 64 && ch >= 24) {
                            val crop = Bitmap.createBitmap(frame, vx, top, vw, ch)
                            // small embedded players have small text: enlarge the crop so the Hindi OCR can read it
                            val sc = if (ch < 260) 2f else 1f
                            val shot = if (sc == 1f) crop else Bitmap.createScaledBitmap(crop, (vw * sc).toInt(), (ch * sc).toInt(), true)
                            val cw = shot.width
                            val res = Tasks.await(rc.process(InputImage.fromBitmap(shot, 0)))
                            // subtitles: centred in the video, not huge, Hindi. Titles, watermarks, comments, menus fail this.
                            val lines = res.textBlocks.flatMap { it.lines }.filter { l ->
                                val b = l.boundingBox ?: return@filter false
                                abs((b.left + b.right) / 2f - cw / 2f) < cw * 0.25f && b.width() < cw * 0.97f &&
                                    b.height() <= vh * sc * 0.16f && devShare(l.text) >= 0.5
                            }.sortedBy { it.boundingBox!!.top }
                            // a real subtitle is one to three short lines; a whole block of text is a page, not a subtitle
                            val joined = if (lines.size in 1..5) lines.joinToString(" ") { it.text } else ""
                            handleOcr(if (joined.length <= 300) joined else "")
                            if (joined.isBlank())
                                State.ocrText = "(video found ${vw}x${vh}; no Hindi subtitle line. saw: ${res.text.replace('\n', ' ').take(40)})"
                            if (shot !== crop) shot.recycle()
                            crop.recycle()
                        }
                    }
                    if (frame !== full) frame.recycle()
                    full.recycle()
                }
            } catch (e: InterruptedException) { break
            } catch (e: Exception) { if (running) State.status = "OCR: ${e.message}" }
            try { Thread.sleep(40) } catch (e: InterruptedException) { break }
        }
    }

    private fun handleOcr(text0: String) {
        val text = text0.replace('\n', ' ').trim()
        State.ocrText = text
        val letters = text.filter { !it.isWhitespace() }
        if (letters.length < 3) { Pipeline.onGone(); return }
        val dev = letters.count { it.code in 0x0900..0x097F }
        if (dev * 2 < letters.length) { Pipeline.onGone(); return }     // only mostly-Hindi lines count as subtitles
        Pipeline.onCaption(applicationContext, text)
    }

    /** Stops threads and releases every capture resource. Safe to call repeatedly. */
    private fun cleanup() {
        running = false
        try { rec?.stop() } catch (_: Exception) {}           // unblocks the audio thread
        audioT?.interrupt(); ocrT?.interrupt()
        try { audioT?.join(600); ocrT?.join(600) } catch (_: Exception) {}
        audioT = null; ocrT = null
        try { rec?.release() } catch (_: Exception) {}; rec = null
        try { vd?.release() } catch (_: Exception) {}; vd = null
        try { reader?.close() } catch (_: Exception) {}; reader = null
        try { recognizer?.close() } catch (_: Exception) {}; recognizer = null
        try { proj?.stop() } catch (_: Exception) {}; proj = null
        State.capturing = false
        resetMotion(); VideoWatch.off(); VideoWatch.tracking = false
    }

    /** Stop button / notification: capture off, speech off, queues empty, display reset. */
    private fun fullStop() {
        cleanup()
        Speaker.stopAll(this)
        Pipeline.reset()
        Prefs(this).enabled = false
        State.resetCapture(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
