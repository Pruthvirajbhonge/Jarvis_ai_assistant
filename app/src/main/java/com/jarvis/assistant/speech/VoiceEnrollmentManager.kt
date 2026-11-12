package com.jarvis.assistant.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("unused")
class VoiceEnrollmentManager(context: Context) {

    val verifier = SpeakerVerificationEngine(context)

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

    fun deleteVoiceprint() {
        verifier.clearEnrollment()
        _sampleProgress.value = 0
        _enrollmentState.value = "Voiceprint deleted."
    }

    fun updateThreshold(newThreshold: Float) {
        verifier.threshold = newThreshold
    }
}
