package com.jarvis.assistant.speech

import com.jarvis.assistant.data.model.SpeakerEmbedding
import com.jarvis.assistant.data.model.VerificationResult
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpeakerVerificationEngine {

    companion object {
        private const val NUM_MEL_FILTERS = 20
        private const val NUM_MFCC_COEFFS = 13
        private const val SAMPLE_RATE = 16000
    }

    /** Extracts a 13-element MFCC feature vector from PCM 16-bit audio buffer. */
    fun extractFeatures(pcm: ShortArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(NUM_MFCC_COEFFS)
        val N = pcm.size

        // 1. Pre-emphasis filter
        val preEmphasized = FloatArray(N)
        preEmphasized[0] = pcm[0].toFloat()
        for (i in 1 until N) {
            preEmphasized[i] = pcm[i] - 0.97f * pcm[i - 1]
        }

        // 2. Hamming Window & FFT Power Spectrum
        val real = FloatArray(N)
        val imag = FloatArray(N)
        for (i in 0 until N) {
            val window = 0.54f - 0.46f * cos(2f * PI.toFloat() * i / (N - 1))
            real[i] = preEmphasized[i] * window
            imag[i] = 0f
        }

        // Simple DFT for power spectrum
        val powerSpectrum = FloatArray(N / 2 + 1)
        val numBins = N / 2 + 1
        for (k in 0 until numBins) {
            var sumReal = 0f
            var sumImag = 0f
            val step = max(1, N / 128) // Decimated DFT for efficiency
            var idx = 0
            while (idx < N) {
                val angle = 2f * PI.toFloat() * k * idx / N
                sumReal += real[idx] * cos(angle) - imag[idx] * sin(angle)
                sumImag += real[idx] * sin(angle) + imag[idx] * cos(angle)
                idx += step
            }
            powerSpectrum[k] = (sumReal * sumReal + sumImag * sumImag) / N
        }

        // 3. Mel Filter Bank
        val melEnergies = FloatArray(NUM_MEL_FILTERS)
        val minMel = hzToMel(0f)
        val maxMel = hzToMel(SAMPLE_RATE / 2f)
        val melStep = (maxMel - minMel) / (NUM_MEL_FILTERS + 1)

        val filterCenterBins = IntArray(NUM_MEL_FILTERS + 2)
        for (i in 0 until NUM_MEL_FILTERS + 2) {
            val mel = minMel + i * melStep
            val hz = melToHz(mel)
            filterCenterBins[i] = min(numBins - 1, max(0, (hz * N / SAMPLE_RATE).toInt()))
        }

        for (m in 1..NUM_MEL_FILTERS) {
            val left = filterCenterBins[m - 1]
            val center = filterCenterBins[m]
            val right = filterCenterBins[m + 1]
            var energy = 0f

            for (k in left..center) {
                if (center != left) {
                    val weight = (k - left).toFloat() / (center - left)
                    energy += weight * powerSpectrum[k]
                }
            }
            for (k in center..right) {
                if (right != center) {
                    val weight = (right - k).toFloat() / (right - center)
                    energy += weight * powerSpectrum[k]
                }
            }
            melEnergies[m - 1] = log10(max(1e-5f, energy))
        }

        // 4. Discrete Cosine Transform (DCT) for MFCCs
        val mfccs = FloatArray(NUM_MFCC_COEFFS)
        for (i in 0 until NUM_MFCC_COEFFS) {
            var sum = 0f
            for (j in 0 until NUM_MEL_FILTERS) {
                sum += melEnergies[j] * cos(PI.toFloat() * i * (j + 0.5f) / NUM_MEL_FILTERS)
            }
            mfccs[i] = sum
        }

        // Normalize vector
        var norm = 0f
        for (v in mfccs) norm += v * v
        norm = sqrt(max(1e-6f, norm))
        for (i in mfccs.indices) mfccs[i] /= norm

        return mfccs
    }

    /** Verifies live audio PCM buffer against enrolled owner embedding. */
    fun verify(pcm: ShortArray, ownerEmbedding: SpeakerEmbedding): VerificationResult {
        if (!ownerEmbedding.isValid || ownerEmbedding.centroids.isEmpty()) {
            return VerificationResult(isMatched = false, confidenceScore = 0f, reason = "No Voiceprint Enrolled")
        }

        val features = extractFeatures(pcm)
        val score = computeCosineSimilarity(features, ownerEmbedding.centroids.toFloatArray())
        val isMatched = score >= ownerEmbedding.threshold

        return VerificationResult(
            isMatched = isMatched,
            confidenceScore = score,
            reason = if (isMatched) "Owner Voice Verified (${(score * 100).toInt()}%)" else "Unverified Speaker (${(score * 100).toInt()}%)"
        )
    }

    /** Computes Cosine Similarity between two normalized feature vectors. */
    fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.isEmpty() || vecB.isEmpty()) return 0f
        val len = min(vecA.size, vecB.size)
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until len) {
            dot += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(0f, 1f)
    }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)
}
