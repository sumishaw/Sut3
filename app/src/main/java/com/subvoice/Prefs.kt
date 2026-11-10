package com.subvoice

import android.content.Context

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("subvoice", Context.MODE_PRIVATE)
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var xttsUrl: String get() = str("xtts", "http://192.168.1.10:8000"); set(v) = put("xtts", v.trim().trimEnd('/'))
    var cbUrl: String get() = str("cb", "http://192.168.1.10:8001"); set(v) = put("cb", v.trim().trimEnd('/'))
    var localUrl: String get() = str("local", "http://127.0.0.1:8002"); set(v) = put("local", v.trim().trimEnd('/'))
    var engine: String get() = str("engine", "local"); set(v) = put("engine", v)       // local (tablet voice + clone) | fast | device | xtts | chatterbox
    var genderMode: String get() = str("gender", "auto"); set(v) = put("gender", v)   // auto | female | male
    var lang: String get() = str("lang", "hi"); set(v) = put("lang", v.trim())
    var voiceId: String get() = str("voice", ""); set(v) = put("voice", v)
    var uploadedTo: String get() = str("upto", ""); set(v) = put("upto", v)
    var devFemale: String get() = str("devF2", ""); set(v) = put("devF2", v)
    var devMale: String get() = str("devM2", ""); set(v) = put("devM2", v)
    var fallback: Boolean get() = bool("fb", true); set(v) = putB("fb", v)
    var enabled: Boolean get() = bool("enabled", true); set(v) = putB("enabled", v)
    var autoRef: Boolean get() = bool("auto", true); set(v) = putB("auto", v)         // clone the video's own speaker
    var ocr: Boolean get() = bool("ocr", true); set(v) = putB("ocr", v)               // read subtitles from the screen
    var pauseVideo: Boolean get() = bool("pause", false); set(v) = putB("pause", v)   // pause video while dubbing
}

object State {
    @Volatile var gender = "female"
    @Volatile var lastCaption = ""
    @Volatile var status = "idle"
    @Volatile var debug = ""
    @Volatile var events = 0
    @Volatile var pkg = ""
    @Volatile var ocrText = ""
    @Volatile var speaker = ""        // id of the person currently heard in the video (spk_f1, spk_m1, spk_m2 ...)
    @Volatile var lastSpeech = 0L     // last time a clearly voiced (speech) sound was heard in the video audio

    /**
     * True while speech is being heard in the playing audio. Subtitles are only read then, so text elsewhere on the
     * screen (menus, comments, articles) is never read. If the video app blocks audio capture (everything silent for
     * a long time) the speech check cannot be done and is skipped.
     */
    fun speechRecent(): Boolean {
        if (!capturing) return true
        if (System.currentTimeMillis() - lastSpeech < 4000) return true
        return audioFrames > 400 && audioLoud == 0
    }
    val profileF0 = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Volatile var audioFrames = 0
    @Volatile var audioLoud = 0
    @Volatile var appVisible = true   // SubVoice screen in front: never OCR our own UI
    val created = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val uploaded = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var capturing = false
    @Volatile var muteUntil = 0L      // ignore captured audio while the on-device TTS is speaking

    /** Forget everything captured during a session (called on Stop). */
    fun resetCapture(ctx: Context) {
        capturing = false; audioFrames = 0; audioLoud = 0; lastSpeech = 0L; speaker = ""; ocrText = ""; debug = ""
        created.clear(); uploaded.clear(); profileF0.clear()
        try { ctx.filesDir.listFiles()?.filter { it.name.startsWith("spk_") }?.forEach { it.delete() } } catch (_: Exception) {}
        status = "stopped"
    }
}
