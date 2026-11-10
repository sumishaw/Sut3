package com.subvoice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One recognised person of the video (own pitch, own timbre, own clone reference). */
class Voice(val id: String, val female: Boolean, var lf: Double, val mf: DoubleArray) {
    var n = 1
    val ring = ShortArray(AudioCaptureService.RING)
    var pos = 0
    var filled = 0
    var lastSave = 0L
}

/** Signal analysis for 16 kHz audio: robust pitch (YIN) and a 12-value spectral-envelope vector (MFCC). */
object VoiceDsp {
    private const val N = 1024
    private const val SR = 16000.0
    private const val NB = 20
    const val NC = 12

    /** clarity (0..1) of the last pitch found by [yin] */
    @Volatile var conf = 0f

    private val hann = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }

    private fun mel(f: Double): Double = 2595.0 * log10(1.0 + f / 700.0)
    private fun imel(m: Double): Double = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

    private val melFb: Array<DoubleArray> = buildFb()
    private val dct: Array<DoubleArray> = Array(NC) { c -> DoubleArray(NB) { b -> cos(PI * (c + 1) * (b + 0.5) / NB) } }

    private fun buildFb(): Array<DoubleArray> {
        val lo = mel(100.0)
        val hi = mel(5500.0)
        val bins = DoubleArray(NB + 2) { imel(lo + (hi - lo) * it / (NB + 1)) / (SR / N) }
        return Array(NB) { b ->
            val fb = DoubleArray(N / 2 + 1)
            val l = bins[b]
            val c = bins[b + 1]
            val r = bins[b + 2]
            val last = min(r.toInt() + 1, N / 2)
            for (k in l.toInt()..last) {
                val kd = k.toDouble()
                if (kd > l && kd <= c) fb[k] = (kd - l) / (c - l)
                else if (kd > c && kd < r) fb[k] = (r - kd) / (r - c)
            }
            fb
        }
    }

    /**
     * Pitch 60-400 Hz of a pre-emphasised 1024-sample window with the YIN method (no octave errors, a clear
     * "voiced" measure). Returns 0 when the window is not clearly voiced (noise, music without a clear pitch...).
     */
    fun yin(x: FloatArray): Float {
        val maxLag = 267
        val minLag = 40
        val w = x.size - maxLag - 1
        val cm = FloatArray(maxLag + 2)
        cm[0] = 1f
        var run = 0.0
        for (tau in 1..maxLag) {
            var s = 0.0
            for (i in 0 until w) {
                val t = (x[i] - x[i + tau]).toDouble()
                s += t * t
            }
            run += s
            cm[tau] = if (run > 0.0) (s * tau / run).toFloat() else 1f
        }
        var tau = minLag
        while (tau < maxLag) {
            if (cm[tau] < 0.3f) {
                while (tau + 1 < maxLag && cm[tau + 1] < cm[tau]) tau++
                val a = cm[tau - 1]
                val b = cm[tau]
                val c = cm[tau + 1]
                val den = a - 2f * b + c
                val sh = if (abs(den) > 1e-9f) (0.5f * (a - c) / den).coerceIn(-1f, 1f) else 0f
                conf = 1f - b
                return (SR / (tau + sh)).toFloat()
            }
            tau++
        }
        return 0f
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] = re[a] + vr; im[a] = im[a] + vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 12 MFCC values (spectral envelope shape, independent of loudness) of a 1024-sample window. */
    fun mfcc(win: ShortArray): DoubleArray {
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        var prev = win[0].toDouble()
        re[0] = prev * hann[0]
        for (i in 1 until N) {
            val v = win[i].toDouble()
            re[i] = (v - 0.97 * prev) * hann[i]
            prev = v
        }
        fft(re, im)
        val e = DoubleArray(NB)
        for (b in 0 until NB) {
            val fb = melFb[b]
            var s = 0.0
            for (k in 0..N / 2) {
                val w = fb[k]
                if (w != 0.0) s += w * (re[k] * re[k] + im[k] * im[k])
            }
            e[b] = ln(s + 1e-3)
        }
        return DoubleArray(NC) { c ->
            var s = 0.0
            for (b in 0 until NB) s += dct[c][b] * e[b]
            s
        }
    }
}

/**
 * Follows WHO is speaking. Every voiced frame (32 ms) adds a pitch value and a spectral vector.
 *  1. gender comes first and from the pitch alone (median of the last 8 voiced frames, border 157 Hz): a man after a
 *     woman (or the reverse) is recognised after 4 voiced frames (~130 ms), never mixed up with a voice of the other gender
 *  2. inside one gender the person is found by pitch + average timbre of the current utterance; a new person is created only
 *     when pitch AND timbre agree that nobody known fits, for several frames in a row
 *  3. known persons are remembered for the whole session, so a returning speaker gets the same voice again
 * Voice ids: spk_f1, spk_f2, ... (women) and spk_m1, spk_m2, ... (men).
 */
class VoiceTracker {
    companion object {
        const val GAP_MS = 350L          // pause that starts a new utterance
        const val K = 8                  // frames for the pitch median
        const val MINF = 4               // frames needed before deciding
        const val SF = 0.09              // pitch spread of one person (ln units)
        const val SM = 4.5               // timbre spread of one person
        const val NT = 30.0              // frames until the timbre of an utterance counts fully
        const val MATCH = 2.0            // distance up to which the voice is a known person
        const val KEEP = 2.2             // the current person is kept while still this close
        const val NEW_F = 2.4            // new person: pitch distance ...
        const val NEW_T = 0.6            // ... together with this timbre distance
        const val NEW_F2 = 3.0           // ... or pitch distance alone
        const val NEW_T2 = 2.4           // ... or timbre distance alone
        const val STREAK = 8             // frames in a row that say "somebody new"
        const val MAXPER = 3             // persons per gender
        const val LR = 0.03              // how fast a known person adapts
        const val AMB_LO = 142.0         // pitch band where the gender is not certain
        const val AMB_HI = 172.0
        const val SPLIT = 157.0          // female above / male below
    }

    val voices = ArrayList<Voice>()
    var cur: Voice? = null
    var lastDist = 0.0

    private val lfs = ArrayList<Double>()
    private val segMf = DoubleArray(VoiceDsp.NC)
    private var segN = 0
    private var streak = 0
    private var last = 0L

    private fun fd(v: Voice, lf: Double): Double = abs(lf - v.lf) / SF

    private fun td(v: Voice): Double {
        var s = 0.0
        for (i in 0 until VoiceDsp.NC) { val d = segMf[i] - v.mf[i]; s += d * d }
        return min(1.0, segN / NT) * sqrt(s / VoiceDsp.NC) / SM
    }

    private fun dist(v: Voice, lf: Double): Double {
        val a = fd(v, lf)
        val b = td(v)
        return sqrt(a * a + b * b)
    }

    private fun add(lf: Double, female: Boolean) {
        val k = voices.count { it.female == female } + 1
        val v = Voice((if (female) "spk_f" else "spk_m") + k, female, lf, segMf.copyOf())
        voices.add(v)
        cur = v
    }

    /** Feed one voiced frame. Returns the voice that is speaking now (null until the first decision). */
    fun onFrame(now: Long, f0: Double, mf: DoubleArray): Voice? {
        if (last != 0L && now - last > GAP_MS) { lfs.clear(); streak = 0; segN = 0 }
        last = now
        lfs.add(ln(f0))
        if (lfs.size > K) lfs.removeAt(0)
        segN++
        for (i in 0 until VoiceDsp.NC) segMf[i] += (mf[i] - segMf[i]) / segN
        if (lfs.size < MINF) return cur

        val s = lfs.sorted()
        val lf = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        val hz = exp(lf)
        val female = hz > SPLIT
        val amb = hz > AMB_LO && hz < AMB_HI
        val cands = if (amb) voices.toList() else voices.filter { it.female == female }
        if (cands.isEmpty()) { streak = 0; add(lf, female); return cur }       // first voice of this gender: no waiting

        var best = cands[0]
        var bd = dist(best, lf)
        for (v in cands) {
            val d = dist(v, lf)
            if (d < bd) { bd = d; best = v }
        }
        lastDist = bd
        if (bd <= MATCH) {
            streak = 0
            val c0 = cur
            if (c0 != null && c0 !== best) {
                val dc = dist(c0, lf)
                if (dc <= KEEP && bd > dc * 0.7) best = c0           // no flapping between two similar persons
            }
            cur = best
            best.n++
            best.lf += LR * (lf - best.lf)
            val w = min(1.0, segN / NT)
            for (i in 0 until VoiceDsp.NC) best.mf[i] += LR * (segMf[i] - best.mf[i]) * w
        } else {
            val a = fd(best, lf)
            val b = td(best)
            val isNew = (a >= NEW_F && b >= NEW_T) || a >= NEW_F2 || b >= NEW_T2
            streak = if (isNew) streak + 1 else 0
            if (!isNew) {
                if (cur == null) cur = best
                return cur
            }
            if (streak >= STREAK) {
                if (voices.count { it.female == female } < MAXPER) { add(lf, female); streak = 0 }
                else cur = best
            }
        }
        return cur
    }
}
