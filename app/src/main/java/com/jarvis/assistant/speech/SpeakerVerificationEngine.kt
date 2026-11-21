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
    private val appCtx = context.applicationContext


    companion object {
        const val MIN_SAMPLES = 3
        const val DEFAULT_THRESHOLD = 0.65f   // cosine similarity; tune with real scores
        private const val PREFS = "jarvis_voiceprint"
        private const val KEY_CENTROID = "centroid"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_ROUTE = "route"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val samples = mutableListOf<FloatArray>()

    private val centroids = java.util.concurrent.ConcurrentHashMap<MicRoute, FloatArray>().also { loadAll(it) }
    @Volatile var threshold: Float = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) {
            field = value.coerceIn(0.3f, 0.95f)
            prefs.edit().putFloat(KEY_THRESHOLD, field).apply()
        }

    /** true when at least one microphone has a voiceprint. */
    val isEnrolled: Boolean get() = centroids.isNotEmpty()

    /** Microphones that have a voiceprint, e.g. "PHONE, BLUETOOTH". */
    val enrolledRoute: String? get() = centroids.keys.map { it.name }.sorted().joinToString(", ").ifEmpty { null }

    fun hasVoiceprint(route: MicRoute): Boolean = centroids.containsKey(route)

    /** A voiceprint is only valid for the microphone it was recorded with (one voiceprint per mic). */
    fun routeMatches(route: MicRoute): Boolean = hasVoiceprint(route)

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
    fun finishEnrollment(route: MicRoute = MicRouting.selected(appCtx)): Float? {
        val snapshot = synchronized(samples) { samples.toList() }
        if (snapshot.size < MIN_SAMPLES) return null
        val dim = snapshot[0].size
        val mean = FloatArray(dim)
        for (s in snapshot) for (i in 0 until dim) mean[i] += s[i] / snapshot.size
        val c = l2(mean)
        val worst = snapshot.minOf { cosine(it, c) }
        centroids[route] = c
        prefs.edit().putString(KEY_CENTROID + "_" + route.name, c.joinToString(",")).apply()
        return worst
    }

    fun clearEnrollment() {
        centroids.clear()
        synchronized(samples) { samples.clear() }
        val e = prefs.edit().remove(KEY_CENTROID).remove(KEY_ROUTE)
        for (r in MicRoute.values()) e.remove(KEY_CENTROID + "_" + r.name)
        e.apply()
    }

    // ---- verification -----------------------------------------------------------------------

    fun verify(xvector: FloatArray): VerificationResult {
        val owner = centroids[MicRouting.selected(appCtx)]
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

    private fun parse(str: String?): FloatArray? =
        str?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.isNotEmpty() }?.toFloatArray()

    private fun loadAll(into: MutableMap<MicRoute, FloatArray>) {
        for (r in MicRoute.values()) parse(prefs.getString(KEY_CENTROID + "_" + r.name, null))?.let { into[r] = it }
        // voiceprint saved by an older version: it belongs to the mic it was recorded with (default PHONE)
        parse(prefs.getString(KEY_CENTROID, null))?.let { old ->
            val r = MicRoute.values().firstOrNull { it.name == prefs.getString(KEY_ROUTE, null) } ?: MicRoute.PHONE
            if (!into.containsKey(r)) into[r] = old
        }
    }
}
