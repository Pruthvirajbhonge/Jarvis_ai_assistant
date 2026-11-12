package com.jarvis.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.IOException

/**
 * Enrolls owner voice by recording [targetSamples] distinct audio samples using Vosk
 * speech & speaker models, extracting x-vectors, and adding them to [engine].
 */
class SpeakerEnroller(
    private val context: Context,
    private val engine: SpeakerVerificationEngine,
    private val onProgress: (done: Int, total: Int, heard: String) -> Unit,
    private val onStatus: (msg: String) -> Unit,
    private val onDone: (ok: Boolean, msg: String) -> Unit,
    private val targetSamples: Int = 5
) {
    private var thread: Thread? = null
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        thread = Thread({ runEnrollment() }, "speaker-enroller").also { it.start() }
    }

    fun stop() {
        isRunning = false
    }

    @SuppressLint("MissingPermission")
    private fun runEnrollment() {
        engine.beginEnrollment()

        var sampleCount = 0
        try {
            onStatus("Loading models for voice enrollment…")

            val mainModelDir = VoskModels.ensure(
                context, VoskModels.MAIN_URL, VoskModels.mainDir(context), onStatus, "voice model, ~40 MB"
            )
            val spkModelDir = VoskModels.ensure(
                context, VoskModels.SPEAKER_URL, VoskModels.speakerDir(context), onStatus, "speaker model, ~13 MB"
            )

            val model = Model(mainModelDir.absolutePath)
            val spkModel = SpeakerModel(spkModelDir.absolutePath)

            val sampleRate = 16000f
            var recorder: EnhancedAudioRecorder? = null
            repeat(4) {
                val r = EnhancedAudioRecorder.create(sampleRate.toInt())
                if (r != null) { recorder = r; return@repeat }
                if (!isRunning) return
                Thread.sleep(400)
            }

            val enhanced = recorder ?: throw IllegalStateException("Microphone is busy.")
            val audio = enhanced.audioRecord

            val agc = AdaptiveGain()
            val rec = Recognizer(model, sampleRate, spkModel)

            onStatus("Say phrase 1 of $targetSamples aloud…")

            rec.use {
                try {
                    enhanced.startRecording()
                    val buf = ShortArray(2048)

                    while (isRunning && sampleCount < targetSamples) {
                        val n = audio.read(buf, 0, buf.size)
                        if (n < 0) throw IOException("Audio read error $n")
                        if (n == 0) continue

                        agc.process(buf, n)

                        if (!rec.acceptWaveForm(buf, n)) continue

                        val o = JSONObject(rec.result)
                        val text = o.optString("text").trim()
                        if (text.isEmpty()) continue

                        val arr = o.optJSONArray("spk")
                        val frames = o.optInt("spk_frames", 0)

                        if (arr == null || frames < 40) {
                            onStatus("Phrase too short. Speak longer and clearly.")
                            continue
                        }

                        val vec = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                        engine.addSample(vec)
                        sampleCount++

                        onProgress(sampleCount, targetSamples, text)

                        if (sampleCount < targetSamples) {
                            onStatus("Recorded $sampleCount/$targetSamples. Speak phrase ${sampleCount + 1}…")
                        }
                    }
                } finally {
                    enhanced.release()
                    try { model.close() } catch (_: Exception) {}
                    try { spkModel.close() } catch (_: Exception) {}
                }
            }

            if (sampleCount >= targetSamples) {
                val quality = engine.finishEnrollment()
                if (quality != null) {
                    onDone(true, "Voiceprint enrolled successfully! (${(quality * 100).toInt()}% quality)")
                } else {
                    onDone(false, "Enrollment failed. Voice samples were not consistent.")
                }
            } else if (!isRunning) {
                onDone(false, "Voiceprint enrollment cancelled.")
            }
        } catch (e: Exception) {
            onDone(false, e.message ?: "Voiceprint enrollment failed.")
        } finally {
            isRunning = false
        }
    }
}
