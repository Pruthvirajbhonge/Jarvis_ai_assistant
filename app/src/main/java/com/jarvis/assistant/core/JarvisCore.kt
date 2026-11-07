package com.jarvis.assistant.core

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.jarvis.assistant.automation.PhoneActionExecutor
import com.jarvis.assistant.automation.PhoneHardwareExecutor
import com.jarvis.assistant.automation.UiAgent
import com.jarvis.assistant.data.db.AppDatabase
import com.jarvis.assistant.data.db.TaskEntity
import com.jarvis.assistant.data.model.PhoneAction
import com.jarvis.assistant.data.model.PhoneTask
import com.jarvis.assistant.data.prefs.JarvisSettings
import com.jarvis.assistant.data.prefs.SettingsStore
import com.jarvis.assistant.data.repo.JarvisRepository
import com.jarvis.assistant.speech.VoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * App-wide singleton shared by MainActivity and the foreground service: one microphone owner,
 * one state stream. The PC link only runs while PC mode is switched on.
 */
class JarvisCore(private val app: Application) {

    val settings = SettingsStore(app)
    val voice = VoiceInteractionManager(app)

    private val executor = PhoneActionExecutor(app)
    private val hardware = PhoneHardwareExecutor(app)
    private val taskDao = AppDatabase.get(app).taskDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val recentNotifications = ArrayDeque<String>()
    private val repository = JarvisRepository(settings, http) { phoneContext() }
    private val uiAgent = UiAgent(executor, repository) { msg -> _state.update { it.copy(note = msg) } }

    private val socket = PcSocketClient(
        http = http,
        settings = settings,
        scope = ioScope,
        onTask = ::runRemoteTask,
        onMetrics = { m ->
            _state.update { it.copy(stats = listOf(Stat("CPU", m.cpu), Stat("RAM", m.ram), Stat("DISK", m.disk))) }
        },
        onConnection = { up -> wsConnected = up; recomputeConnection() }
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var latest = JarvisSettings()
    @Volatile private var wsConnected = false
    @Volatile private var pollReachable: Boolean? = null
    private var backgroundJob: Job? = null
    private var statsJob: Job? = null

    init {
        voice.onModeChanged = { m -> _state.update { it.copy(mode = m) } }
        voice.onWakeWord = {
            wakeScreen()
            voice.startCommandSession()
        }
        voice.onTranscript = { handleCommand(it) }
        voice.onError = { msg -> _state.update { it.copy(note = msg) } }

        // Mirror persisted settings into the UI state (works even before the service starts).
        scope.launch {
            settings.flow.collect { s ->
                latest = s
                _state.update { it.copy(pcMode = s.pcMode) }
                recomputeConnection()
            }
        }
    }

    // ---- commands ---------------------------------------------------------------------------

    fun handleCommand(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        scope.launch {
            // "PC mode" / "phone mode" are handled locally so they always work, even offline.
            ModeCommand.parse(text)?.let { toPc ->
                setPcMode(toPc)
                val msg = if (toPc) "PC mode on, sir. I'll send your commands to the computer."
                else "Phone mode on, sir."
                _state.update { it.copy(reply = msg, note = null) }
                voice.speak(msg)
                return@launch
            }

            // Phone mode: volume, flashlight and calls are executed on the phone IMMEDIATELY,
            // without waiting for (or depending on) the AI. Works offline too.
            if (!latest.pcMode) {
                val local = hardware.tryHandleText(text)
                if (local != null && !local.fallThrough) {
                    _state.update { it.copy(busy = false, reply = local.spoken, lastAction = local.spoken, note = null) }
                    voice.speak(local.spoken)
                    return@launch
                }
            }

            _state.update { it.copy(busy = true, note = null) }
            val result = repository.ask(text)
            val action = result.action
            var spoken = result.reply
            var executed: String? = null

            if (action != null) {
                val isUiTask = action.action.equals("ui_task", true)
                if (isUiTask && spoken.isNotBlank()) { // long multi-step job: acknowledge first
                    _state.update { it.copy(reply = spoken) }
                    voice.speak(spoken)
                }
                val outcome = runAction(action) // really changes the phone
                executed = outcome
                // Hardware actions are confirmed with what REALLY happened, not what the AI claimed.
                spoken = if (isUiTask || hardware.handles(action.action) || spoken.isBlank()) outcome else spoken
            }
            if (spoken.isBlank()) spoken = "No response."
            _state.update { it.copy(busy = false, reply = spoken, lastAction = executed, note = result.note) }
            voice.speak(spoken)
        }
    }

    private suspend fun runAction(a: PhoneAction): String =
        if (a.action.equals("ui_task", true)) uiAgent.run(a.str("goal", "task").orEmpty())
        else executor.execute(a)

    private suspend fun runRemoteTask(task: PhoneTask): String {
        val fresh = taskDao.insert(TaskEntity(task.id, task.action.action, "running", System.currentTimeMillis())) != -1L
        if (!fresh) return "duplicate"
        val result = runAction(task.action)
        taskDao.updateStatus(task.id, "done")
        taskDao.prune(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
        _state.update { it.copy(reply = task.reply.ifBlank { result }) }
        if (task.reply.isNotBlank()) voice.speak(task.reply)
        return result
    }

    fun setPcMode(on: Boolean) {
        scope.launch { settings.setPcMode(on) }
    }

    suspend fun testAi(s: JarvisSettings): String = repository.testProviders(s)

    // ---- background lifecycle (called by the foreground service) ----------------------------

    fun startBackground() {
        if (backgroundJob?.isActive == true) return
        backgroundJob = scope.launch {
            launch {
                settings.flow.map { it.wakeWordEnabled }.distinctUntilChanged().collect { enabled ->
                    if (enabled) voice.startWake() else voice.stopWake()
                }
            }
            launch {
                settings.flow.map { Triple(it.pcMode, it.pcHost, it.token) }.distinctUntilChanged()
                    .collect { (pc, _, _) ->
                        statsJob?.cancel()
                        if (pc) {
                            socket.restart()
                            statsJob = launch { pcPollLoop() }
                        } else {
                            socket.stop()
                            wsConnected = false
                            pollReachable = null
                            statsJob = launch { phoneStatsLoop() }
                        }
                        recomputeConnection()
                    }
            }
        }
    }

    fun stopBackground() {
        backgroundJob?.cancel()
        backgroundJob = null
        statsJob?.cancel()
        statsJob = null
        socket.stop()
        voice.stopWake()
    }

    private suspend fun pcPollLoop() {
        while (true) {
            val status = repository.pollStatus()
            pollReachable = status.reachable
            status.metrics?.let { m ->
                _state.update { it.copy(stats = listOf(Stat("CPU", m.cpu), Stat("RAM", m.ram), Stat("DISK", m.disk))) }
            }
            recomputeConnection()
            delay(if (status.reachable) 15_000L else 8_000L)
        }
    }

    private suspend fun phoneStatsLoop() {
        while (true) {
            _state.update { it.copy(stats = phoneStats()) }
            delay(15_000L)
        }
    }

    private fun recomputeConnection() {
        val s = latest
        val conn = when {
            s.pcMode -> if (wsConnected || pollReachable == true) Connection.PC_ONLINE else Connection.PC_OFFLINE
            s.hasAnyAi -> Connection.PHONE_READY
            else -> Connection.NO_AI
        }
        _state.update { it.copy(connection = conn) }
    }

    // ---- phone info -------------------------------------------------------------------------

    private fun batteryPercent(): Int? {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    private fun phoneStats(): List<Stat> {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ram = if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else null
        val st = StatFs(Environment.getDataDirectory().path)
        val disk = if (st.totalBytes > 0) ((st.totalBytes - st.availableBytes) * 100 / st.totalBytes).toInt() else null
        return listOf(Stat("BAT", batteryPercent()), Stat("RAM", ram), Stat("DISK", disk))
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    fun wakeScreen() {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Jarvis::ScreenWake"
        ).acquire(3_000L)
    }

    fun recordNotification(line: String) {
        synchronized(recentNotifications) {
            recentNotifications.addFirst(line)
            while (recentNotifications.size > 10) recentNotifications.removeLast()
        }
    }

    private fun phoneContext(): String {
        val now = SimpleDateFormat("EEEE, d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val notes = synchronized(recentNotifications) { recentNotifications.joinToString("\n") }
        return "Now: $now\nBattery: ${batteryPercent() ?: "unknown"}%\n" +
            "Recent notifications:\n${notes.ifBlank { "(none)" }}"
    }
}
