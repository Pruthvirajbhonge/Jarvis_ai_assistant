package com.jarvis.assistant.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeakerEmbedding(
    val centroids: List<Float> = emptyList(),
    val sampleCount: Int = 0,
    val threshold: Float = 0.72f,
    val enrolledAt: Long = System.currentTimeMillis()
) {
    val isValid: Boolean
        get() = centroids.isNotEmpty() && sampleCount > 0
}

@Serializable
data class VerificationResult(
    val isMatched: Boolean = false,
    val confidenceScore: Float = 0f,
    val reason: String = "Unverified"
)
