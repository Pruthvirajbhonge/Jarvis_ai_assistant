package com.jarvis.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
            context: Context? = null,
            useHardwareFx: Boolean = false,
            route: MicRoute = MicRoute.PHONE,
            sampleRate: Int = 16000,
            channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
            audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeMultiplier: Int = 4
        ): EnhancedAudioRecorder? {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf <= 0) return null
            val bufferSize = maxOf(minBuf, 4096) * bufferSizeMultiplier

            // VOICE_RECOGNITION first: made for speech recognition, no call-mode side effects.
            // VOICE_COMMUNICATION switches the phone to "in-communication" audio processing
            // (volume/routing changes, ducking of other audio) and is only a last resort.
            val sources = if (route == MicRoute.BLUETOOTH) {
                // Bluetooth SCO works with the communication source.
                intArrayOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
                )
            } else {
                // VOICE_RECOGNITION first: made for speech recognition, no call-mode side effects.
                intArrayOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                )
            }

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

            // Pin the input to the SELECTED microphone. Never let Android silently pick another one, and
            // never record from a device that is not connected (return null so the caller reports it).
            if (context != null) {
                val dev = when (route) {
                    MicRoute.PHONE -> MicRouting.builtinMic(context)
                    MicRoute.WIRED -> MicRouting.wiredMic(context)
                    MicRoute.BLUETOOTH -> BluetoothMicRouter.findInput(context)
                }
                if (dev == null && route != MicRoute.PHONE) { audio.release(); return null }
                if (dev != null) {
                    val ok = try { audio.setPreferredDevice(dev) } catch (_: Exception) { false }
                    if (!ok && route != MicRoute.PHONE) { audio.release(); return null }
                }
            }

            var ns: NoiseSuppressor? = null
            if (useHardwareFx && NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(audio.audioSessionId)?.apply {
                        enabled = true
                    }
                } catch (_: Exception) {}
            }

            var aec: AcousticEchoCanceler? = null
            if (useHardwareFx && AcousticEchoCanceler.isAvailable()) {
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
