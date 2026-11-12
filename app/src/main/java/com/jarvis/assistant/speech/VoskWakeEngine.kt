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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Same adaptive gain as before, shared by the wake engine AND the enroller so audio matches. */
class AdaptiveGain {
    private var gain = START_GAIN

    fun process(buf: ShortArray, n: Int) {
        var sumSq = 0.0
        var peak = 1
        for (i in 0 until n) {
            val v = buf[i].toInt()
            sumSq += v.toDouble() * v
            peak = max(peak, if (v < 0) -v else v)
        }
        val rms = sqrt(sumSq / n)
        // Noise Gate: Zero out ambient background murmur/silence below noise gate floor
        if (rms < SILENCE_GATE_RMS) {
            for (i in 0 until n) buf[i] = 0
            gain = START_GAIN
            return
        }
        if (rms > NOISE_FLOOR_RMS) {
            val wanted = (TARGET_RMS / rms).toFloat().coerceIn(1f, MAX_GAIN)
            gain += (wanted - gain) * (if (wanted < gain) 0.5f else 0.1f)
        }
        gain = min(gain, PEAK_LIMIT / peak)
        gain = max(gain, 1f)
        if (gain > 1.01f) {
            for (i in 0 until n) buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private companion object {
        const val START_GAIN = 2f
        const val MAX_GAIN = 8f
        const val TARGET_RMS = 3000.0
        const val NOISE_FLOOR_RMS = 40.0
        const val SILENCE_GATE_RMS = 25.0
        const val PEAK_LIMIT = 30000f
    }
}

/**
 * Always-on offline "Jarvis" detector. Opens the mic ONCE.
 *
 * Owner-only mode: when [speakerEngine] is given, a wake/command is accepted ONLY if the
 * FINISHED utterance's speaker x-vector matches the enrolled owner. Fails closed: if the owner
 * is not enrolled, the engine reports a failure instead of accepting everybody.
 *
 * NOTE: wake is evaluated on final results only (after you stop speaking), never on partials.
 */
class VoskWakeEngine(
    private val context: Context,
    private val onWake: (capturedText: String?) -> Unit,
    private val onStatus: (String) -> Unit,
    /** (message, retryable). retryable=false means retrying cannot help (e.g. not enrolled). */
    private val onFailure: (String, Boolean) -> Unit,
    private val speakerEngine: SpeakerVerificationEngine? = null,
    private val isModeA: (() -> Boolean)? = null,
    private val isVoiceAuthEnabled: (() -> Boolean)? = null
) {
    private val lock = Any()
    private var model: Model? = null
    private var spkModel: SpeakerModel? = null
    private var active = false
    @Volatile private var generation = 0
    private var worker: Thread? = null

    fun start() {
        val g: Int
        val prev: Thread?
        synchronized(lock) {
            if (active) return
            active = true
            g = ++generation
            prev = worker
        }
        val t = Thread({ runLoop(g, prev) }, "jarvis-wake")
        synchronized(lock) { worker = t }
        t.start()
    }

    fun stop() {
        synchronized(lock) {
            active = false
            generation++
        }
    }

    @SuppressLint("MissingPermission")
    private fun runLoop(g: Int, prev: Thread?) {
        var retryable = true
        val authActive = isVoiceAuthEnabled?.invoke() == true
        try {
            // Fail closed only when voice auth is enabled: never accept everyone when auth is ON but not enrolled.
            if (authActive && speakerEngine != null && !speakerEngine.isEnrolled) {
                retryable = false
                throw IllegalStateException("Owner voice is not enrolled. Enroll your voice in Settings.")
            }
            // Wait for the previous loop to release the microphone (stop() is asynchronous).
            // Without this, a quick stop()->start() finds the mic busy and fails.
            try { prev?.join(2000) } catch (_: InterruptedException) {}
            val m = ensureModel()
            val spk = if (authActive && speakerEngine != null) ensureSpeakerModel() else null
            if (g != generation) return

            var recorder: EnhancedAudioRecorder? = null
            for (attempt in 1..4) { // mic may still be held for a moment by the system recognizer / TTS
                val r = EnhancedAudioRecorder.create(SAMPLE_RATE.toInt())
                if (r != null) { recorder = r; break }
                if (g != generation) return
                Thread.sleep(400)
            }
            val enhanced = recorder ?: throw IllegalStateException("Microphone is busy.")
            val audio = enhanced.audioRecord

            val agc = AdaptiveGain()
            val rec = if (spk != null) Recognizer(m, SAMPLE_RATE, spk) else Recognizer(m, SAMPLE_RATE)

            rec.use {
                try {
                    enhanced.startRecording()
                    val buf = ShortArray(2048)
                    while (g == generation) {
                        val n = audio.read(buf, 0, buf.size)
                        if (n < 0) throw IOException("Audio read error $n")
                        if (n == 0) continue

                        agc.process(buf, n)

                        // Only act on a FINISHED utterance (full phrase -> reliable x-vector).
                        if (!rec.acceptWaveForm(buf, n)) continue

                        val o = JSONObject(rec.result)
                        val text = o.optString("text")
                        if (text.isEmpty()) continue

                        val modeA = isModeA?.invoke() == true
                        val accepted = WAKE.containsMatchIn(text) ||
                            (modeA && COMMAND_KEYWORDS.containsMatchIn(text))
                        if (!accepted) continue

                        if (authActive && speakerEngine != null) {
                            val arr = o.optJSONArray("spk")
                            val frames = o.optInt("spk_frames", 0)
                            if (arr == null || frames < MIN_SPK_FRAMES) {
                                onStatus("Too short to verify speaker, ignored.")
                                continue
                            }
                            val vec = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                            val result = speakerEngine.verify(vec)
                            onStatus(result.reason) // shows the score: use it to tune the threshold
                            if (!result.isMatched) continue // not the owner: keep listening
                        }

                        var fire = false
                        synchronized(lock) {
                            if (g == generation) {
                                active = false
                                generation++
                                fire = true
                            }
                        }
                        if (fire) onWake(text)
                        break
                    }
                } finally {
                    enhanced.release()
                }
            }
        } catch (e: Exception) {
            synchronized(lock) {
                if (g == generation) {
                    active = false
                    generation++
                }
            }
            onFailure(e.message ?: "Wake engine failed.", retryable)
        }
    }

    // ---- models -----------------------------------------------------------------------------

    private fun ensureModel(): Model = synchronized(modelLock) {
        model ?: run {
            val dir = VoskModels.ensure(
                context, VoskModels.MAIN_URL, VoskModels.mainDir(context), onStatus, "voice model, ~40 MB"
            )
            Model(dir.absolutePath).also { model = it }
        }
    }

    private fun ensureSpeakerModel(): SpeakerModel = synchronized(modelLock) {
        spkModel ?: run {
            val dir = VoskModels.ensure(
                context, VoskModels.SPEAKER_URL, VoskModels.speakerDir(context), onStatus, "speaker model, ~13 MB"
            )
            SpeakerModel(dir.absolutePath).also { spkModel = it }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000f
        const val MIN_SPK_FRAMES = 50 // ~0.5 s of speech; raise to 100 for stricter verification
        val modelLock = Any()
        val WAKE = Regex("\\b(jarvis|jar vis|jarvish|jarves|jarvas|javis)\\b", RegexOption.IGNORE_CASE)
        // Mode A: ONLY known command words (the old `text.length >= 8` rule accepted any chatter).
        val COMMAND_KEYWORDS = Regex(
            """\b(open|close|turn|set|call|dial|play|stop|search|show|find|volume|torch|flashlight|whatsapp|youtube|spotify|chrome|freefire|notes|camera|calculator|gallery|settings|mute|unmute|up|down|back|home|recents)\b|उघड|उघडा|चालू|चालु|बंद|कॉल|फोन|सर्च|शोधा|आवाज""",
            RegexOption.IGNORE_CASE
        )
    }
}
