package com.jarvis.assistant.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("unused")
class VoiceEnrollmentManager(
    private val context: Context,
    val verifier: SpeakerVerificationEngine
) {
    private val modelLock = Any()
    @Volatile private var speechModel: Model? = null
    @Volatile private var speakerModel: SpeakerModel? = null

    /**
     * Extract a real Vosk speaker x-vector from captured PCM.
     * Raw PCM samples are NOT speaker embeddings and must never be enrolled directly.
     */
    suspend fun extractXvector(pcm: ShortArray): FloatArray? = withContext(Dispatchers.IO) {
        if (pcm.size < 16_000) return@withContext null

        val model = getSpeechModel()
        val speaker = getSpeakerModel()
        Recognizer(model, 16_000f, speaker).use { recognizer ->
            recognizer.acceptWaveForm(pcm, pcm.size)
            val json = if (recognizer.finalResult.isNotBlank()) {
                JSONObject(recognizer.finalResult)
            } else {
                JSONObject(recognizer.result)
            }
            val frames = json.optInt("spk_frames", 0)
            val values = json.optJSONArray("spk") ?: return@withContext null
            if (frames < 50 || values.length() != 128) return@withContext null

            val vector = FloatArray(values.length()) { values.getDouble(it).toFloat() }
            if (vector.any { !it.isFinite() } || vector.all { it == 0f }) null else vector
        }
    }

    private fun getSpeechModel(): Model = synchronized(modelLock) {
        speechModel ?: Model(
            VoskModels.ensure(
                context, VoskModels.MAIN_URL, VoskModels.mainDir(context),
                { _ -> }, "voice model"
            ).absolutePath
        ).also { speechModel = it }
    }

    private fun getSpeakerModel(): SpeakerModel = synchronized(modelLock) {
        speakerModel ?: SpeakerModel(
            VoskModels.ensure(
                context, VoskModels.SPEAKER_URL, VoskModels.speakerDir(context),
                { _ -> }, "speaker model"
            ).absolutePath
        ).also { speakerModel = it }
    }

    private val _enrollmentState = MutableStateFlow("Tap 'Enroll Voiceprint' to begin.")
    val enrollmentState: StateFlow<String> = _enrollmentState.asStateFlow()

    private val _sampleProgress = MutableStateFlow(0)
    val sampleProgress: StateFlow<Int> = _sampleProgress.asStateFlow()

    val isEnrolled: Boolean get() = verifier.isEnrolled

    fun startEnrollment() {
        verifier.beginEnrollment()
        _sampleProgress.value = 0
        _enrollmentState.value = "Sample 1/3: Say 'Jarvis, this is my voice.'"
    }

    fun addXvectorSample(xvector: FloatArray): Boolean {
        if (xvector.isEmpty()) return false
        verifier.addSample(xvector)
        val count = verifier.sampleCount
        _sampleProgress.value = count

        if (count < SpeakerVerificationEngine.MIN_SAMPLES) {
            _enrollmentState.value = "Sample $count/3 recorded! Speak next phrase."
            return false
        } else {
            val result = verifier.finishEnrollment()
            if (result != null) {
                _enrollmentState.value = "Voiceprint Enrollment Complete! Speaker verified."
                return true
            } else {
                _enrollmentState.value = "Enrollment failed. Please try again."
                return false
            }
        }
    }

    fun cancelEnrollment() {
        verifier.beginEnrollment()
        _sampleProgress.value = 0
        _enrollmentState.value = "Enrollment cancelled."
    }

    fun deleteVoiceprint() {
        verifier.clearEnrollment()
        _sampleProgress.value = 0
        _enrollmentState.value = "Voiceprint deleted."
    }

    fun updateThreshold(newThreshold: Float) {
        verifier.threshold = newThreshold
    }
}
