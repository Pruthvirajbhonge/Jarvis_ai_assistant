package com.jarvis.assistant.speech

import kotlin.math.log10
import kotlin.math.sqrt

/** Cheap audio quality gate: used before enrolling a sample and before trusting a speaker score. */
object AudioQuality {
    class Report(val ok: Boolean, val reason: String)

    // Configurable thresholds.
    @Volatile var minSeconds = 0.6f
    @Volatile var minRmsDb = -42f      // dBFS of the whole utterance (quieter = too far / too faint)
    @Volatile var maxClipPercent = 2f  // % of samples near full scale = distorted

    fun check(pcm: ShortArray, n: Int, sampleRate: Int = 16000): Report {
        if (n <= 0) return Report(false, "No audio.")
        val seconds = n.toFloat() / sampleRate
        if (seconds < minSeconds) return Report(false, "Too short (${"%.1f".format(seconds)} s). Speak a full sentence.")
        var sumSq = 0.0
        var clipped = 0
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            sumSq += v.toDouble() * v
            if (v >= 32000 || v <= -32000) clipped++
        }
        val rms = sqrt(sumSq / n)
        val db = if (rms < 1.0) -99f else (20.0 * log10(rms / 32768.0)).toFloat()
        if (db < minRmsDb) return Report(false, "Too quiet (${db.toInt()} dBFS). Speak closer to the mic.")
        val clipPct = clipped * 100f / n
        if (clipPct > maxClipPercent) return Report(false, "Distorted (clipping ${"%.1f".format(clipPct)}%). Speak a bit softer.")
        return Report(true, "ok")
    }
}
