package com.jarvis.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One owner for the microphone: it runs either the "Hey Jarvis" wake loop (WAKE) or a single
 * command session (COMMAND), and also owns Text-to-Speech. Everything runs on the main thread.
 *
 * Wake detection is free and keyless: SpeechRecognizer is restarted in a loop and every
 * result is checked for "jarvis". It is NOT a low-power neural engine; the engine can be
 * swapped for openWakeWord later by replacing only the WAKE branch in this class.
 */
class VoiceInteractionManager(context: Context) {

    enum class Mode { IDLE, WAKE, COMMAND }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    /** Natural J.A.R.V.I.S. voice (Edge neural online, tuned Android voice offline). */
    private val voiceOut = JarvisVoiceManager(appContext)
    private var speaking = false
    private var wakeEnabled = false
    private var sessionId = 0

    /** Continuous offline wake engine: keeps ONE steady mic stream, no restarts, no beeps. */
    private var useEngine = true
    private val engine = VoskWakeEngine(
        context = appContext,
        onWake = { main.post { onEngineWake() } },
        onStatus = { msg -> main.post { onError?.invoke(msg) } },
        onFailure = { msg -> main.post { engineFailed(msg) } }
    )

    @Volatile
    var mode: Mode = Mode.IDLE
        private set

    var onWakeWord: (() -> Unit)? = null
    var onTranscript: ((String) -> Unit)? = null
    var onModeChanged: ((Mode) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAudioRms: ((Float) -> Unit)? = null

    /** BCP-47 tag. Use "mr-IN" or "hi-IN" for Marathi / Hindi commands. */
    var recognitionLocale: String = "en-IN"

    private val wakeRegex = Regex(
        "(hey|hi|ok|okay)?\\s*(jarvis|jar vis|jarvish|जार्विस|जर्विस|जार्वीस)",
        RegexOption.IGNORE_CASE
    )

    init {
        // Pause the wake-word mic while Jarvis talks; resume when the reply has completely finished.
        voiceOut.onSpeechDone = { speechFinished() }
    }

    // ---- public API -------------------------------------------------------------------------

    fun startWake() = runOnMain {
        wakeEnabled = true
        if (mode == Mode.IDLE && !speaking) enterWake()
    }

    fun stopWake() = runOnMain {
        wakeEnabled = false
        engine.stop()
        if (mode == Mode.WAKE) {
            main.removeCallbacksAndMessages(null)
            cancelRecognizer()
            setMode(Mode.IDLE)
        }
    }

    fun startCommandSession() = runOnMain {
        engine.stop() // free the mic for the command recognizer
        voiceOut.stop()
        speaking = false
        beginRecognition(Mode.COMMAND)
    }

    fun speak(text: String) = runOnMain {
        if (text.isBlank()) return@runOnMain
        if (mode == Mode.WAKE) { // don't let the wake loop hear our own voice
            engine.stop()
            main.removeCallbacksAndMessages(null)
            cancelRecognizer()
            setMode(Mode.IDLE)
        }
        speaking = true
        voiceOut.speak(text)
    }

    // ---- internals --------------------------------------------------------------------------

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun setMode(m: Mode) {
        if (mode != m) {
            mode = m
            onModeChanged?.invoke(m)
        }
    }

    private fun speechFinished() {
        main.post {
            speaking = false
            if (wakeEnabled && mode == Mode.IDLE) scheduleWake(600)
        }
    }

    private fun scheduleWake(delayMs: Long) {
        main.postDelayed({
            if (wakeEnabled && !speaking && mode != Mode.COMMAND) enterWake()
        }, delayMs)
    }

    private fun enterWake() {
        if (useEngine) {
            main.removeCallbacksAndMessages(null)
            cancelRecognizer()
            setMode(Mode.WAKE)
            engine.start()
        } else {
            beginRecognition(Mode.WAKE)
        }
    }

    private fun onEngineWake() {
        if (mode != Mode.WAKE) return
        setMode(Mode.IDLE)
        onWakeWord?.invoke()
    }

    /** Model missing / mic busy: fall back to the old SpeechRecognizer loop so wake still works. */
    private fun engineFailed(msg: String) {
        useEngine = false
        onError?.invoke("$msg Using basic wake mode.")
        if (wakeEnabled && mode != Mode.COMMAND && !speaking) beginRecognition(Mode.WAKE)
    }

    private fun cancelRecognizer() {
        sessionId++
        recognizer?.let {
            try { it.cancel() } catch (e: Exception) {}
            try { it.destroy() } catch (e: Exception) {}
        }
        recognizer = null
    }

    private fun beginRecognition(m: Mode) {
        main.removeCallbacksAndMessages(null)
        cancelRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            setMode(Mode.IDLE)
            onError?.invoke("Speech recognition is not available on this device.")
            return
        }
        setMode(m)
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        r.setRecognitionListener(Listener(sessionId, m))
        recognizer = r
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        })
    }

    private fun endSession() {
        cancelRecognizer()
        setMode(Mode.IDLE)
        if (wakeEnabled) scheduleWake(400)
    }

    private fun handleResults(m: Mode, list: List<String>) {
        when (m) {
            Mode.COMMAND -> {
                val text = list.firstOrNull()?.trim().orEmpty()
                endSession()
                if (text.isNotEmpty()) onTranscript?.invoke(text)
                else onError?.invoke("I didn't catch that.")
            }
            Mode.WAKE -> {
                val hit = list.firstOrNull { wakeRegex.containsMatchIn(it) }
                if (hit == null) {
                    scheduleWake(150)
                    return
                }
                val remainder = wakeRegex.replace(hit, "").trim()
                if (remainder.length >= 3) { // "Hey Jarvis open YouTube" in one breath
                    endSession()
                    onTranscript?.invoke(remainder)
                } else {
                    cancelRecognizer()
                    setMode(Mode.IDLE)
                    onWakeWord?.invoke()
                }
            }
            Mode.IDLE -> Unit
        }
    }

    private fun handleError(m: Mode, error: Int) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            cancelRecognizer()
            wakeEnabled = false
            setMode(Mode.IDLE)
            onError?.invoke("Microphone permission is required.")
            return
        }
        if (m == Mode.COMMAND) {
            endSession()
            val noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            onError?.invoke(if (noSpeech) "I didn't catch that." else "Speech error ($error).")
        } else {
            cancelRecognizer()
            scheduleWake(
                when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1500L
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 5000L
                    else -> 250L
                }
            )
        }
    }

    private inner class Listener(private val id: Int, private val m: Mode) : RecognitionListener {
        override fun onResults(results: Bundle?) {
            if (id != sessionId) return
            handleResults(m, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        }

        override fun onError(error: Int) {
            if (id != sessionId) return
            handleError(m, error)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (id != sessionId) return
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            onAudioRms?.invoke(normalized)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
