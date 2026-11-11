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

    sealed class VoiceEvent {
        data class DirectCommand(val command: String) : VoiceEvent()
        data object WakeOnly : VoiceEvent()
    }

    companion object {
        private val WAKE_STRIP = Regex(
            """\b(?:(?:hey|hi|ok|okay)[,\s]*)?(jarvis|jar\s*vis|jarvish|jarves|jarvas|javis|जार्विस|जर्विस|जार्वीस)\b[,.!?]*""",
            RegexOption.IGNORE_CASE
        )

        /** The single canonical wake-phrase extractor used by every voice input path. */
        fun extractCommand(captured: String?): String =
            captured.orEmpty().replace(WAKE_STRIP, " ").replace(Regex("""\s+"""), " ").trim()
    }

    enum class Mode {
        IDLE, WAKE, COMMAND
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    private val voiceOut = JarvisVoiceManager(appContext)

    private var speaking = false
    private var wakeEnabled = false
    private var sessionId = 0

    // Command timeout: maximum wait for user to START speaking.
    private var commandTimeoutRunnable: Runnable? = null

    private val COMMAND_START_TIMEOUT = 12_000L

    // Silence detection hints for SpeechRecognizer.
    private val commandCompleteSilence = 1_500L
    private val commandPossibleSilence  = 1_000L

    // Vosk continuous wake engine.
    private var useEngine = true

    val verifier = SpeakerVerificationEngine(appContext)
    val enrollmentManager = VoiceEnrollmentManager(appContext)
    @Volatile var isMicMuted: Boolean = false
    var isModeASupplier: (() -> Boolean)? = null

    private val engine = VoskWakeEngine(
        context = appContext,
        onWake = { capturedText ->
            main.post { onEngineWake(capturedText) }
        },
        onStatus = { msg ->
            main.post { onError?.invoke(msg) }
        },
        onFailure = { msg ->
            main.post { engineFailed(msg) }
        },
        speakerEngine = verifier,
        isModeA = { isModeASupplier?.invoke() == true }
    )

    @Volatile
    var mode: Mode = Mode.IDLE
        private set

    var onVoiceEvent: ((VoiceEvent) -> Unit)? = null
    var onTranscript: ((String) -> Unit)? = null
    var onModeChanged: ((Mode) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAudioRms: ((Float) -> Unit)? = null

    var recognitionLocale: String = "en-IN"

    private val wakeRegex = Regex(
        "(hey|hi|ok|okay)?\\s*(jarvis|jar vis|jarvish|जार्विस|जर्विस|जार्वीस)",
        RegexOption.IGNORE_CASE
    )

    private var onNextSpeechDone: (() -> Unit)? = null

    init {
        voiceOut.onSpeechDone = {
            val pending = onNextSpeechDone
            onNextSpeechDone = null
            if (pending != null) {
                pending()
            } else {
                speechFinished()
            }
        }
    }

    // --------------------------------------------------
    // PUBLIC API
    // --------------------------------------------------

    fun startWake() = runOnMain {
        wakeEnabled = true
        // Explicitly re-enabling the listener retries Vosk, but never falls back to an
        // unauthenticated Android SpeechRecognizer session.
        useEngine = true

        if (isMicMuted) {
            onError?.invoke("Microphone is muted.")
            return@runOnMain
        }

        if (mode == Mode.IDLE && !speaking) {
            enterWake()
        }
    }

    fun stopWake() = runOnMain {
        wakeEnabled = false

        engine.stop()

        cancelCommandTimeout()
        main.removeCallbacksAndMessages(null)

        if (mode == Mode.WAKE || mode == Mode.COMMAND) {
            cancelRecognizer()
            setMode(Mode.IDLE)
        }
    }

    fun startCommandSession() = runOnMain {
        if (isMicMuted) {
            onError?.invoke("Microphone is muted.")
            return@runOnMain
        }

        cancelCommandTimeout()
        main.removeCallbacksAndMessages(null)
        cancelRecognizer()
        voiceOut.stop()
        speaking = false
        onNextSpeechDone = null
        setMode(Mode.COMMAND)
        // Do not use SpeechRecognizer here: it has no x-vector and cannot authenticate
        // the speaker. Vosk command capture applies the same enrolled-owner verifier.
        engine.startCommand()
    }

    /**
     * Speaks a short wake greeting aloud (e.g. "Yes, sir?") and automatically opens
     * the command microphone ONLY AFTER the greeting has finished speaking.
     */
    fun speakThenCommand(greeting: String) = runOnMain {
        if (greeting.isBlank()) {
            startCommandSession()
            return@runOnMain
        }

        engine.stop()
        main.removeCallbacksAndMessages(null)
        if (mode == Mode.WAKE || mode == Mode.COMMAND) {
            cancelRecognizer()
            setMode(Mode.IDLE)
        }

        cancelCommandTimeout()

        speaking = true
        onNextSpeechDone = {
            speaking = false
            startCommandSession()
        }
        voiceOut.speak(greeting)
    }

    fun speak(text: String) = runOnMain {
        if (text.isBlank()) return@runOnMain

        if (mode == Mode.WAKE || mode == Mode.COMMAND) {
            engine.stop()
            main.removeCallbacksAndMessages(null)
            cancelRecognizer()
            setMode(Mode.IDLE)
        }

        cancelCommandTimeout()

        speaking = true
        voiceOut.speak(text)
    }

    // --------------------------------------------------
    // MAIN THREAD
    // --------------------------------------------------

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    private fun setMode(m: Mode) {
        if (mode != m) {
            mode = m
            onModeChanged?.invoke(m)
        }
    }

    // --------------------------------------------------
    // SPEECH OUTPUT
    // --------------------------------------------------

    private fun speechFinished() {
        main.post {
            speaking = false

            if (wakeEnabled && mode == Mode.IDLE) {
                scheduleWake(600)
            }
        }
    }

    // --------------------------------------------------
    // WAKE WORD ENGINE
    // --------------------------------------------------

    private fun scheduleWake(delayMs: Long) {
        main.postDelayed({
            if (wakeEnabled && !speaking && mode != Mode.COMMAND) {
                enterWake()
            }
        }, delayMs)
    }

    private fun enterWake() {
        if (!useEngine || isMicMuted || speaking || mode != Mode.IDLE) return
        cancelCommandTimeout()
        cancelRecognizer()
        setMode(Mode.WAKE)
        engine.start()
    }

    private fun onEngineWake(capturedText: String? = null) {
        if (mode != Mode.WAKE && mode != Mode.COMMAND) return

        val wasCommandSession = mode == Mode.COMMAND
        setMode(Mode.IDLE)

        if (wasCommandSession) {
            val command = extractCommand(capturedText)
            if (command.isNotBlank()) onTranscript?.invoke(command)
            else onError?.invoke("I didn't catch that.")
            return
        }

        val command = extractCommand(capturedText)
        if (command.isNotBlank()) {
            onVoiceEvent?.invoke(VoiceEvent.DirectCommand(command))
        } else {
            onVoiceEvent?.invoke(VoiceEvent.WakeOnly)
        }
    }

    private fun engineFailed(msg: String) {
        // Keep owner verification fail-closed, but do not permanently disable the
        // background listener after a transient AudioRecord/model error.
        setMode(Mode.IDLE)
        onError?.invoke("$msg Voice listener will retry; owner verification remains enabled.")
        if (wakeEnabled && !isMicMuted && !speaking) {
            scheduleWake(2_000L)
        }
    }

    // --------------------------------------------------
    // COMMAND TIMEOUT
    // --------------------------------------------------

    private fun cancelCommandTimeout() {
        commandTimeoutRunnable?.let {
            main.removeCallbacks(it)
        }

        commandTimeoutRunnable = null
    }

    private fun startCommandTimeout(currentId: Int) {
        cancelCommandTimeout()

        val timeout = Runnable {
            if (
                mode == Mode.COMMAND &&
                sessionId == currentId
            ) {
                commandTimeoutRunnable = null

                handleError(
                    Mode.COMMAND,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                )
            }
        }

        commandTimeoutRunnable = timeout

        main.postDelayed(
            timeout,
            COMMAND_START_TIMEOUT
        )
    }

    // --------------------------------------------------
    // SPEECH RECOGNIZER
    // --------------------------------------------------

    private fun cancelRecognizer() {
        cancelCommandTimeout()

        sessionId++

        recognizer?.let {
            try {
                it.cancel()
            } catch (_: Exception) {
            }

            try {
                it.destroy()
            } catch (_: Exception) {
            }
        }

        recognizer = null
    }

    private fun beginRecognition(m: Mode) {

        cancelCommandTimeout()
        cancelRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            setMode(Mode.IDLE)

            onError?.invoke(
                "Speech recognition is not available on this device."
            )

            return
        }

        setMode(m)

        val r = SpeechRecognizer.createSpeechRecognizer(appContext)

        val currentId = sessionId

        r.setRecognitionListener(
            Listener(currentId, m)
        )

        recognizer = r

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                recognitionLocale
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                recognitionLocale
            )

            putExtra(
                "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES",
                arrayOf("en-IN", "en-US")
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )

            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
            )

            putExtra(
                RecognizerIntent.EXTRA_CALLING_PACKAGE,
                appContext.packageName
            )

            // Silence detection.
            // These are hints; actual behavior depends on
            // the Android recognition service.

            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                commandCompleteSilence
            )

            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                commandPossibleSilence
            )

            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                0L
            )
        }

        try {
            r.startListening(intent)

            // Only COMMAND mode needs a safety timer.
            // Timer ends when the user starts speaking.

            if (m == Mode.COMMAND) {
                startCommandTimeout(currentId)
            }

        } catch (_: Exception) {

            if (currentId == sessionId) {
                handleError(
                    m,
                    SpeechRecognizer.ERROR_CLIENT
                )
            }
        }
    }

    // --------------------------------------------------
    // END SESSION
    // --------------------------------------------------

    private fun endSession() {
        cancelCommandTimeout()

        cancelRecognizer()
        setMode(Mode.IDLE)
        // The command handler's TTS completion is the single point that restarts wake.
        // Restarting here races command execution and can open two microphone sessions.
    }

    // --------------------------------------------------
    // SPEECH RESULTS
    // --------------------------------------------------

    private fun handleResults(
        m: Mode,
        list: List<String>
    ) {

        when (m) {

            Mode.COMMAND -> {

                cancelCommandTimeout()

                val text = list
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()

                endSession()

                if (text.isNotEmpty()) {
                    onTranscript?.invoke(text)
                } else {
                    onError?.invoke("I didn't catch that.")
                }
            }

            Mode.WAKE -> {

                val hit = list.firstOrNull {
                    wakeRegex.containsMatchIn(it)
                }

                if (hit == null) {
                    scheduleWake(150)
                    return
                }

                val remainder = wakeRegex
                    .replace(hit, "")
                    .trim()

                if (remainder.length >= 3) {

                    endSession()

                    onTranscript?.invoke(extractCommand(hit))

                } else {

                    cancelRecognizer()

                    setMode(Mode.IDLE)

                    onVoiceEvent?.invoke(VoiceEvent.WakeOnly)
                }
            }

            Mode.IDLE -> Unit
        }
    }

    // --------------------------------------------------
    // ERROR HANDLING
    // --------------------------------------------------

    private fun handleError(
        m: Mode,
        error: Int
    ) {

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {

            cancelRecognizer()

            wakeEnabled = false

            setMode(Mode.IDLE)

            onError?.invoke(
                "Microphone permission is required."
            )

            return
        }

        if (m == Mode.COMMAND) {

            cancelCommandTimeout()

            endSession()

            val noSpeech =
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            onError?.invoke(
                if (noSpeech) {
                    "I didn't catch that."
                } else {
                    "Speech error ($error)."
                }
            )

        } else {

            cancelRecognizer()

            if (useEngine) scheduleWake(
                when (error) {

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> 1500L

                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 5000L

                    else -> 250L
                }
            )
        }
    }

    // --------------------------------------------------
    // RECOGNITION LISTENER
    // --------------------------------------------------

    private inner class Listener(
        private val id: Int,
        private val m: Mode
    ) : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            // Recognition service is ready.
        }

        override fun onBeginningOfSpeech() {

            if (id != sessionId) return

            if (m == Mode.COMMAND) {

                // User has started speaking.
                // Cancel the 12-second START timeout.
                // This prevents long commands being cut off.

                cancelCommandTimeout()
            }
        }

        override fun onResults(results: Bundle?) {

            if (id != sessionId) return

            handleResults(
                m,
                results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                ).orEmpty()
            )
        }

        override fun onError(error: Int) {

            if (id != sessionId) return

            handleError(m, error)
        }

        override fun onRmsChanged(rmsdB: Float) {

            if (id != sessionId) return

            val normalized = (
                    (rmsdB + 2f) / 12f
                    ).coerceIn(0f, 1f)

            onAudioRms?.invoke(normalized)
        }

        override fun onEndOfSpeech() {
            // Speech ended.
            // Recognition service will deliver results.
        }

        override fun onBufferReceived(buffer: ByteArray?) {
        }

        override fun onPartialResults(partialResults: Bundle?) {
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?
        ) {
        }
    }
}