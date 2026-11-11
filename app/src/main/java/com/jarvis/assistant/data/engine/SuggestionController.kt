package com.jarvis.assistant.data.engine

import android.content.Context
import com.jarvis.assistant.data.db.AppDatabase
import com.jarvis.assistant.data.db.DismissedSuggestionEntity
import com.jarvis.assistant.data.model.ContextSuggestion
import com.jarvis.assistant.data.prefs.JarvisSettings
import com.jarvis.assistant.data.prefs.SettingsStore
import com.jarvis.assistant.speech.VoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class SuggestionController(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val suggestionEngine: ContextSuggestionEngine,
    private val voiceManager: VoiceInteractionManager
) {
    private val db = AppDatabase.get(context).suggestionDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeSuggestion = MutableStateFlow<ContextSuggestion?>(null)
    val activeSuggestion: StateFlow<ContextSuggestion?> = _activeSuggestion.asStateFlow()

    private var lastSuggestionTime = 0L
    private val hourlyHistory = ArrayDeque<Long>()

    fun onContextEvent(activePkg: String?) {
        scope.launch {
            val s = settingsStore.current()
            if (!s.proactiveSuggestionsEnabled) return@launch

            if (!canTriggerSuggestion(s)) return@launch

            val suggestion = suggestionEngine.generateSuggestion(activePkg) ?: return@launch

            val hash = hashCommand(suggestion.command)
            val isDismissed = try { db.isDismissed(hash, System.currentTimeMillis() - DISMISS_COOL_OFF_MS) } catch (_: Exception) { false }
            if (isDismissed) return@launch

            // Record suggestion trigger time
            val now = System.currentTimeMillis()
            lastSuggestionTime = now
            synchronized(hourlyHistory) {
                hourlyHistory.addLast(now)
                pruneOldHourlyHistory(now)
            }

            _activeSuggestion.value = suggestion
        }
    }

    fun dismissSuggestion(suggestion: ContextSuggestion) {
        _activeSuggestion.value = null
        scope.launch {
            try {
                val hash = hashCommand(suggestion.command)
                db.recordDismissed(
                    DismissedSuggestionEntity(
                        commandHash = hash,
                        commandText = suggestion.command,
                        dismissedAt = System.currentTimeMillis()
                    )
                )
                db.pruneDismissed(System.currentTimeMillis() - DISMISS_COOL_OFF_MS)
            } catch (_: Exception) {}
        }
    }

    fun acceptSuggestion(suggestion: ContextSuggestion): String {
        _activeSuggestion.value = null
        return suggestion.command
    }

    fun clearActive() {
        _activeSuggestion.value = null
    }

    private fun canTriggerSuggestion(s: JarvisSettings): Boolean {
        // Voice / Speech Safety checks: DO NOT interrupt voice recognition or TTS
        if (voiceManager.mode == VoiceInteractionManager.Mode.COMMAND) return false

        val now = System.currentTimeMillis()

        // 5-minute minimum gap check
        if (now - lastSuggestionTime < MIN_INTERVAL_MS) return false

        // Max 3 suggestions per hour check
        synchronized(hourlyHistory) {
            pruneOldHourlyHistory(now)
            if (hourlyHistory.size >= s.maxSuggestionsPerHour) return false
        }

        return true
    }

    private fun pruneOldHourlyHistory(now: Long) {
        val oneHourAgo = now - 3_600_000L
        while (hourlyHistory.isNotEmpty() && hourlyHistory.first < oneHourAgo) {
            hourlyHistory.removeFirst()
        }
    }

    private fun hashCommand(cmd: String): String =
        cmd.lowercase().trim().replace(" ", "").hashCode().toString()

    private companion object {
        const val MIN_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        const val DISMISS_COOL_OFF_MS = 12 * 3600 * 1000L // 12 hours cool-off for dismissed items
    }
}
