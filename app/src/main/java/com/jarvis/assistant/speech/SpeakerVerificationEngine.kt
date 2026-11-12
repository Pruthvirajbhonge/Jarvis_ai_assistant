package com.jarvis.assistant.speech

import android.content.Context
import com.jarvis.assistant.data.model.VerificationResult
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Owner-voice verification based on Vosk speaker x-vectors (128-dim, one per finished utterance).
 * Replaces the old MFCC-mean approach, which could not tell speakers apart.
 *
 * Enrollment: beginEnrollment() -> addSample(xvector) x N -> finishEnrollment().
 * Verification: verify(xvector) compares against the stored owner centroid (cosine similarity).
 */
class SpeakerVerificationEngine(context: Context) {

    companion object {
        const val MIN_SAMPLES = 3
        const val DEFAULT_THRESHOLD = 0.65f   // cosine similarity; tune with real scores
        private const val PREFS = "jarvis_voiceprint"
        private const val KEY_CENTROID = "centroid"
        private const val KEY_THRESHOLD = "threshold"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val samples = mutableListOf<FloatArray>()

    @Volatile private var centroid: FloatArray? = loadCentroid()
    @Volatile var threshold: Float = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) {
            field = value.coerceIn(0.3f, 0.95f)
            prefs.edit().putFloat(KEY_THRESHOLD, field).apply()
        }

    val isEnrolled: Boolean get() = centroid != null
    val sampleCount: Int get() = synchronized(samples) { samples.size }

    // ---- enrollment -------------------------------------------------------------------------

    fun beginEnrollment() = synchronized(samples) { samples.clear() }

    fun addSample(xvector: FloatArray) {
        synchronized(samples) { samples.add(l2(xvector)) }
    }

    /**
     * Builds the owner centroid. Returns the WORST self-similarity among the samples
     * (low value = inconsistent recordings, e.g. noise or another speaker), or null if
     * there were not enough samples.
     */
    fun finishEnrollment(): Float? {
        val snapshot = synchronized(samples) { samples.toList() }
        if (snapshot.size < MIN_SAMPLES) return null
        val dim = snapshot[0].size
        val mean = FloatArray(dim)
        for (s in snapshot) for (i in 0 until dim) mean[i] += s[i] / snapshot.size
        val c = l2(mean)
        val worst = snapshot.minOf { cosine(it, c) }
        centroid = c
        prefs.edit().putString(KEY_CENTROID, c.joinToString(",")).apply()
        return worst
    }

    fun clearEnrollment() {
        centroid = null
        synchronized(samples) { samples.clear() }
        prefs.edit().remove(KEY_CENTROID).apply()
    }

    // ---- verification -----------------------------------------------------------------------

    fun verify(xvector: FloatArray): VerificationResult {
        val owner = centroid
            ?: return VerificationResult(isMatched = false, confidenceScore = 0f, reason = "No Voiceprint Enrolled")
        val score = cosine(l2(xvector), owner)
        val ok = score >= threshold
        val pct = (score * 100).toInt()
        return VerificationResult(
            isMatched = ok,
            confidenceScore = score,
            reason = if (ok) "Owner Voice Verified ($pct%)" else "Unverified Speaker ($pct%, need ${(threshold * 100).toInt()}%)"
        )
    }

    // ---- math -------------------------------------------------------------------------------

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val len = min(a.size, b.size)
        if (len == 0) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in 0 until len) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun l2(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun loadCentroid(): FloatArray? =
        prefs.getString(KEY_CENTROID, null)
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.toFloatArray()
}
