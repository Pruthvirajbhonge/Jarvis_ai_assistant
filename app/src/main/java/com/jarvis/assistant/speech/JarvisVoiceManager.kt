package com.jarvis.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * J.A.R.V.I.S.'s voice.
 *
 * 1. ONLINE  -> Microsoft Edge Neural TTS streamed over WebSocket and played with MediaPlayer:
 *               en-US-ChristopherNeural (English) / mr-IN-AarohiNeural (Marathi/Hindi text).
 * 2. OFFLINE -> (or if Edge fails/times out) Android TextToSpeech tuned to sound like J.A.R.V.I.S.:
 *               Locale.UK, pitch 0.92, speed 1.05 and a male voice picked from tts.voices.
 *
 * All public functions are safe to call from the main thread. Callbacks run on the main thread.
 */
class JarvisVoiceManager(context: Context) {

    /** Fired when a reply starts / completely finishes (or fails). Used to pause the wake-word mic. */
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechDone: (() -> Unit)? = null

    /** Turn the Edge neural voice off to always use the offline voice. */
    @Volatile var neuralEnabled: Boolean = true

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val edge = EdgeNeuralTts(appContext)

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var speakId = 0
    private var doneFor = -1

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingLocal: Pair<String, Int>? = null
    private var maleVoice: Voice? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = localFinished(utteranceId)

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) = localFinished(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = localFinished(utteranceId)
                })
                tts?.let { maleVoice = pickMaleVoice(it) }
                Log.i(TAG, "Offline voice: ${maleVoice?.name ?: "engine default"}")
                val pending = pendingLocal
                pendingLocal = null
                if (pending != null) speakLocal(pending.first, pending.second)
            } else {
                val pending = pendingLocal
                pendingLocal = null
                if (pending != null) finish(pending.second)
            }
        }
    }

    // ---- public API -------------------------------------------------------------------------

    /** Speaks [text], replacing whatever is currently being said. */
    fun speak(text: String) {
        val clean = prepare(text)
        if (clean.isEmpty()) return
        stop()
        val id = ++speakId
        onSpeechStart?.invoke()

        job = scope.launch {
            var played = false
            if (neuralEnabled && isOnline()) {
                try {
                    val file = edge.synthesize(clean, voiceFor(clean))
                    if (id == speakId) played = play(file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Neural voice failed, using the offline voice: ${e.message}")
                }
            }
            if (id != speakId) return@launch
            if (played) finish(id) else speakLocal(clean, id)
        }
    }

    /** Stops speaking immediately (no onSpeechDone callback). */
    fun stop() {
        speakId++
        job?.cancel()
        job = null
        pendingLocal = null
        releasePlayer()
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    // ---- neural playback --------------------------------------------------------------------

    /** Returns true when the file played to the end, false if playback failed. */
    private suspend fun play(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        player = mp

        fun end(ok: Boolean) {
            if (player === mp) player = null
            try { mp.release() } catch (e: Exception) { /* already released */ }
            if (cont.isActive) cont.resume(ok)
        }

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // same stream as the "set volume" command
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { end(true) }
            mp.setOnErrorListener { _, _, _ -> end(false); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            end(false)
        }

        cont.invokeOnCancellation {
            if (player === mp) player = null
            try { mp.release() } catch (e: Exception) { /* already released */ }
        }
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (e: Exception) { /* not started */ }
            try { it.release() } catch (e: Exception) { /* already released */ }
        }
        player = null
    }

    // ---- offline fallback -------------------------------------------------------------------

    private fun speakLocal(text: String, id: Int) {
        val engine = tts
        if (engine == null) {
            finish(id)
            return
        }
        if (!ttsReady) {
            // TextToSpeech initialization is asynchronous. Queue instead of silently
            // completing the response before the engine becomes ready.
            pendingLocal = text to id
            return
        }
        if (isDevanagari(text)) {
            val r = engine.setLanguage(Locale.forLanguageTag("mr-IN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.forLanguageTag("hi-IN"))
            }
        } else {
            engine.setLanguage(Locale.UK)
            maleVoice?.let { engine.voice = it }
        }
        engine.setPitch(0.92f)        // deep, authoritative
        engine.setSpeechRate(1.05f)   // crisp

        val limit = TextToSpeech.getMaxSpeechInputLength() - 1
        val r = engine.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, null, "jv-$id")
        if (r != TextToSpeech.SUCCESS) finish(id)
    }

    private fun localFinished(utteranceId: String?) {
        val id = utteranceId?.removePrefix("jv-")?.toIntOrNull() ?: return
        main.post { finish(id) }
    }

    /** Picks the best male English voice installed on the phone (UK first, offline first). */
    private fun pickMaleVoice(engine: TextToSpeech): Voice? {
        val voices = try { engine.voices } catch (e: Exception) { null } ?: return null
        fun isMale(v: Voice): Boolean {
            val n = v.name.lowercase()
            return (n.contains("male") && !n.contains("female")) || MALE_VOICE_TAGS.any { n.contains(it) }
        }
        return voices
            .filter { it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .filter(::isMale)
            .sortedWith(
                compareByDescending<Voice> { it.locale.country == "GB" }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
            )
            .firstOrNull()
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun finish(id: Int) {
        if (id == speakId && doneFor != id) {
            doneFor = id
            onSpeechDone?.invoke()
        }
    }

    private fun voiceFor(text: String) = if (isDevanagari(text)) VOICE_MARATHI else VOICE_ENGLISH

    private fun isDevanagari(text: String) = text.any { it in '\u0900'..'\u097F' }

    /** Removes markdown/URLs so the voice doesn't read symbols aloud, and caps very long replies. */
    private fun prepare(text: String): String = text
        .replace(Regex("""https?://\S+"""), "link")
        .replace(Regex("""[*_`#>~]+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(900)

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val TAG = "JarvisVoice"
        const val VOICE_ENGLISH = "en-US-ChristopherNeural"
        const val VOICE_MARATHI = "mr-IN-AarohiNeural"

        /** Google TTS voice ids that are male (en-GB gbb/gbd/rjs, en-US iol/iom/tpd). */
        val MALE_VOICE_TAGS = listOf("-gbb-", "-gbd-", "-rjs-", "-iol-", "-iom-", "-tpd-")
    }
}

/**
 * Microsoft Edge "Read Aloud" neural TTS (the same service the PC's edge-tts uses).
 * NOTE: this is an unofficial endpoint. If Microsoft changes it, [JarvisVoiceManager] automatically
 * falls back to the offline voice, and only the constants below need updating.
 */
internal class EdgeNeuralTts(context: Context) {

    private val cacheDir = File(context.cacheDir, "jarvis-tts").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Returns an .mp3 file for [text]. Repeated phrases are served from a small disk cache. */
    suspend fun synthesize(text: String, voice: String): File = withContext(Dispatchers.IO) {
        val cached = File(cacheDir, sha1("$voice|$PITCH|$RATE|$text") + ".mp3")
        if (cached.exists() && cached.length() > 0) return@withContext cached

        val audio = ByteArrayOutputStream()
        val finished = CompletableDeferred<Unit>()

        val socket = client.newWebSocket(buildRequest(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(configMessage())
                webSocket.send(ssmlMessage(text, voice))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) finished.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary frame = [2-byte header length][text headers][mp3 bytes]
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headerLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (b.size < 2 + headerLen) return
                val headers = String(b, 2, headerLen, Charsets.UTF_8)
                if (headers.contains("Path:audio")) {
                    synchronized(audio) { audio.write(b, 2 + headerLen, b.size - 2 - headerLen) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val http = response?.code?.let { " (HTTP $it)" }.orEmpty()
                finished.completeExceptionally(IOException("Edge TTS failed: ${t.message}$http"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished.isCompleted) finished.completeExceptionally(IOException("Edge TTS closed early"))
            }
        })

        try {
            withTimeoutOrNull(9_000L) { finished.await() } ?: throw IOException("Edge TTS timed out")
            val bytes = synchronized(audio) { audio.toByteArray() }
            if (bytes.isEmpty()) throw IOException("Edge TTS returned no audio")
            cached.writeBytes(bytes)
            trimCache()
            cached
        } finally {
            socket.cancel()
        }
    }

    private fun buildRequest(): Request {
        val url = "$WSS_URL?TrustedClientToken=$TOKEN" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=1-$CHROMIUM_VERSION" +
            "&ConnectionId=${uuid()}"
        return Request.Builder().url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "muid=${uuid().uppercase()};")
            .build()
    }

    /** Anti-abuse token: SHA-256 of the current time (5-minute steps, Windows ticks) + the client token. */
    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + 11_644_473_600L
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return fmt.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun configMessage() =
        "X-Timestamp:${timestamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" +
            "\r\n"

    private fun ssmlMessage(text: String, voice: String): String {
        val lang = voice.take(5) // "en-US" / "mr-IN"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$voice'><prosody pitch='$PITCH' rate='$RATE' volume='+0%'>${escape(text)}</prosody></voice></speak>"
        return "X-RequestId:${uuid()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp()}Z\r\nPath:ssml\r\n\r\n$ssml"
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun uuid() = UUID.randomUUID().toString().replace("-", "")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun trimCache() {
        cacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
    }

    private companion object {
        const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val CHROMIUM_VERSION = "130.0.2849.68" // update if Microsoft starts rejecting it
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.2849.68"
        const val PITCH = "+0Hz"
        const val RATE = "+0%"
    }
}
