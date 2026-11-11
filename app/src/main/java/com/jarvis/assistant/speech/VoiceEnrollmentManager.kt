package com.jarvis.assistant.speech

import android.content.Context
import com.jarvis.assistant.data.model.JarvisJson
import com.jarvis.assistant.data.model.SpeakerEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class VoiceEnrollmentManager(private val context: Context) {

    private val verifier = SpeakerVerificationEngine()
    private val samples = mutableListOf<FloatArray>()

    private val _enrollmentState = MutableStateFlow("Tap 'Start Recording' to begin voice enrollment.")
    val enrollmentState: StateFlow<String> = _enrollmentState.asStateFlow()

    private val _sampleProgress = MutableStateFlow(0)
    val sampleProgress: StateFlow<Int> = _sampleProgress.asStateFlow()

    @Volatile
    var currentEmbedding: SpeakerEmbedding? = null
        private set

    init {
        loadEnrolledVoiceprint()
    }

    fun startEnrollment() {
        samples.clear()
        _sampleProgress.value = 0
        _enrollmentState.value = "Sample 1/3: Say 'Jarvis, this is my voice.'"
    }

    fun addSample(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return false
        val features = verifier.extractFeatures(pcm)
        samples.add(features)
        val count = samples.size
        _sampleProgress.value = count

        if (count < REQUIRED_SAMPLES) {
            _enrollmentState.value = "Sample ${count + 1}/3 recorded! Say 'System online, voice authentication active.'"
            return false
        } else {
            val averagedCentroid = computeAverageCentroid(samples)
            val embedding = SpeakerEmbedding(
                centroids = averagedCentroid.toList(),
                sampleCount = count,
                threshold = DEFAULT_THRESHOLD,
                enrolledAt = System.currentTimeMillis()
            )
            currentEmbedding = embedding
            saveEnrolledVoiceprint(embedding)
            _enrollmentState.value = "Voiceprint Enrollment Complete! Speaker verified."
            return true
        }
    }

    fun deleteVoiceprint() {
        samples.clear()
        currentEmbedding = null
        _sampleProgress.value = 0
        _enrollmentState.value = "Voiceprint deleted."
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun updateThreshold(newThreshold: Float) {
        val current = currentEmbedding ?: return
        val updated = current.copy(threshold = newThreshold.coerceIn(0.50f, 0.90f))
        currentEmbedding = updated
        saveEnrolledVoiceprint(updated)
    }

    private fun computeAverageCentroid(sampleList: List<FloatArray>): FloatArray {
        if (sampleList.isEmpty()) return FloatArray(13)
        val dim = sampleList[0].size
        val avg = FloatArray(dim)
        for (vec in sampleList) {
            for (i in vec.indices) avg[i] += vec[i]
        }
        val count = sampleList.size.toFloat()
        for (i in avg.indices) avg[i] /= count
        return avg
    }

    private fun saveEnrolledVoiceprint(embedding: SpeakerEmbedding) {
        try {
            val json = JarvisJson.encodeToString(embedding)
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VOICEPRINT, json)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadEnrolledVoiceprint() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_VOICEPRINT, null)
            if (!json.isNullOrBlank()) {
                currentEmbedding = JarvisJson.decodeFromString<SpeakerEmbedding>(json)
                _enrollmentState.value = "Enrolled voiceprint active."
                _sampleProgress.value = REQUIRED_SAMPLES
            }
        } catch (_: Exception) {}
    }

    private companion object {
        const val REQUIRED_SAMPLES = 3
        const val DEFAULT_THRESHOLD = 0.72f
        const val PREF_NAME = "jarvis_voiceprint_prefs"
        const val KEY_VOICEPRINT = "enrolled_voiceprint_json"
    }
}
