package com.jarvis.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Always-on, fully offline "Jarvis" detector. It opens the microphone ONCE and keeps reading
 * audio, so there is no start/stop flicker and no recognizer beeps.
 *
 * The small Indian-English Vosk model (~40 MB) is downloaded on first use and cached.
 * Threading: [start]/[stop] may be called from any thread; callbacks come from a worker thread.
 */
class VoskWakeEngine(
    private val context: Context,
    private val onWake: (capturedText: String?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onSpeakerVerify: ((pcm: ShortArray) -> Boolean)? = null,
    private val isModeA: (() -> Boolean)? = null
) {
    private val lock = Any()
    private var model: Model? = null
    private var active = false
    @Volatile private var generation = 0

    fun start() {
        val g: Int
        synchronized(lock) {
            if (active) return
            active = true
            g = ++generation
        }
        Thread({ runLoop(g) }, "jarvis-wake").start()
    }

    /** Releases the microphone (the loaded model stays in memory for a fast restart). */
    fun stop() {
        synchronized(lock) {
            active = false
            generation++
        }
    }

    @SuppressLint("MissingPermission")
    private fun runLoop(g: Int) {
        try {
            val m = ensureModel()
            if (g != generation) return

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 4096) * 4
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                throw IllegalStateException("Microphone is not available.")
            }

            // 1) Hardware AGC (only where the phone supports it)
            val agc = if (AutomaticGainControl.isAvailable()) {
                try {
                    AutomaticGainControl.create(audio.audioSessionId)?.apply { enabled = true }
                } catch (e: Exception) { null }
            } else null

            var gain = START_GAIN

            Recognizer(m, SAMPLE_RATE).use { rec ->
                try {
                    audio.startRecording()
                    val buf = ShortArray(2048)
                    while (g == generation) {
                        val n = audio.read(buf, 0, buf.size)
                        if (n < 0) throw IOException("Audio read error $n")
                        if (n == 0) continue

                        // 2) Adaptive software gain for far / quiet voices.
                        var sumSq = 0.0
                        var peak = 1
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            sumSq += v.toDouble() * v
                            peak = max(peak, if (v < 0) -v else v)
                        }
                        val rms = sqrt(sumSq / n)
                        if (rms > NOISE_FLOOR_RMS) { // never boost pure background noise
                            val wanted = (TARGET_RMS / rms).toFloat().coerceIn(1f, MAX_GAIN)
                            // fall fast (avoid clipping), rise slowly (avoid pumping)
                            gain += (wanted - gain) * (if (wanted < gain) 0.5f else 0.1f)
                        }
                        gain = min(gain, PEAK_LIMIT / peak) // never clip
                        gain = max(gain, 1f)
                        if (gain > 1.01f) {
                            for (i in 0 until n) {
                                buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                        val json = if (rec.acceptWaveForm(buf, n)) rec.result else rec.partialResult
                        val o = JSONObject(json)
                        val text = o.optString("text").ifEmpty { o.optString("partial") }
                        val matchesWake = WAKE.containsMatchIn(text)
                        val matchesCommand = COMMAND_KEYWORDS.containsMatchIn(text) || text.length >= 8
                        val directModeA = isModeA?.invoke() == true && matchesCommand

                        if (text.isNotEmpty() && (matchesWake || directModeA)) {
                            // Check speaker verification if configured
                            if (onSpeakerVerify != null && !onSpeakerVerify.invoke(buf)) {
                                continue // Reject non-owner speaker
                            }
                            var fire = false
                            val capturedText = text
                            synchronized(lock) {
                                if (g == generation) {
                                    active = false
                                    generation++
                                    fire = true
                                }
                            }
                            if (fire) onWake(capturedText)
                            break
                        }
                    }
                } finally {
                    try { agc?.release() } catch (e: Exception) {}
                    try { audio.stop() } catch (e: Exception) {}
                    audio.release()
                }
            }
        } catch (e: Exception) {
            synchronized(lock) {
                if (g == generation) {
                    active = false
                    generation++
                }
            }
            onFailure(e.message ?: "Wake engine failed.")
        }
    }

    // ---- model ------------------------------------------------------------------------------

    private fun ensureModel(): Model = synchronized(modelLock) {
        model ?: run {
            val dir = File(context.filesDir, "vosk/model")
            if (!File(dir, "am").exists()) {
                onStatus("Downloading voice model (one time, ~40 MB)…")
                downloadModel(dir)
                onStatus("")
            }
            Model(dir.absolutePath).also { model = it }
        }
    }

    private fun downloadModel(target: File) {
        val zip = File(context.cacheDir, "vosk-model.zip")
        OkHttpClient().newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Voice model download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IOException("Voice model download was empty.")
            body.byteStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        }

        val tmp = File(context.filesDir, "vosk/tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(tmp, entry.name)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath)) throw SecurityException("Bad zip entry")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        val inner = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!inner.renameTo(target)) throw IOException("Could not install the voice model.")
        tmp.deleteRecursively()
        zip.delete()
    }

    private companion object {
        const val SAMPLE_RATE = 16000f

        // Adaptive gain tuning
        const val START_GAIN = 2f
        const val MAX_GAIN = 8f            // loudest boost for very distant voices
        const val TARGET_RMS = 3000.0      // about -20 dBFS, a comfortable level for Vosk
        const val NOISE_FLOOR_RMS = 40.0   // below this it's silence: don't amplify it
        const val PEAK_LIMIT = 30000f      // keep headroom under 32767 so audio never clips
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
        val modelLock = Any()
        val WAKE = Regex("\\b(jarvis|jar vis|jarvish|jarves|jarvas|javis)\\b", RegexOption.IGNORE_CASE)
        val COMMAND_KEYWORDS = Regex(
            """\b(open|close|turn|set|call|dial|play|stop|search|show|find|volume|torch|flashlight|whatsapp|youtube|spotify|chrome|freefire|notes|camera|calculator|gallery|settings|mute|unmute|up|down|back|home|recents)\b|उघड|उघडा|चालू|चालु|बंद|बंद|कॉल|फोन|सर्च|शोधा|आवाज""",
            RegexOption.IGNORE_CASE
        )
    }
}
