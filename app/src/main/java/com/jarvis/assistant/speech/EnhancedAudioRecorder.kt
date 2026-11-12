package com.jarvis.assistant.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/**
 * Creates an [AudioRecord] configured with dual-mic hardware beamforming
 * (`VOICE_COMMUNICATION` with fallback to `VOICE_RECOGNITION`), and attaches
 * Android hardware [NoiseSuppressor] and [AcousticEchoCanceler] when supported.
 */
class EnhancedAudioRecorder private constructor(
    val audioRecord: AudioRecord,
    private val noiseSuppressor: NoiseSuppressor?,
    private val echoCanceler: AcousticEchoCanceler?
) {

    fun startRecording() {
        audioRecord.startRecording()
    }

    fun stop() {
        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        } catch (_: Exception) {}
    }

    fun release() {
        stop()
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { audioRecord.release() } catch (_: Exception) {}
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun create(
            sampleRate: Int = 16000,
            channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
            audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeMultiplier: Int = 4
        ): EnhancedAudioRecorder? {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf <= 0) return null
            val bufferSize = maxOf(minBuf, 4096) * bufferSizeMultiplier

            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )

            var record: AudioRecord? = null
            for (src in sources) {
                try {
                    val a = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                    if (a.state == AudioRecord.STATE_INITIALIZED) {
                        record = a
                        break
                    }
                    a.release()
                } catch (_: Exception) {}
            }

            val audio = record ?: return null

            var ns: NoiseSuppressor? = null
            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(audio.audioSessionId)?.apply {
                        enabled = true
                    }
                } catch (_: Exception) {}
            }

            var aec: AcousticEchoCanceler? = null
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(audio.audioSessionId)?.apply {
                        enabled = true
                    }
                } catch (_: Exception) {}
            }

            return EnhancedAudioRecorder(audio, ns, aec)
        }
    }
}
