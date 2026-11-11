package com.jarvis.assistant.data.engine

import android.content.Context
import com.jarvis.assistant.data.db.AppDatabase
import com.jarvis.assistant.data.db.TaskContextRecordEntity
import com.jarvis.assistant.data.model.ContextSuggestion
import com.jarvis.assistant.data.model.JarvisJson
import com.jarvis.assistant.data.model.SuggestionPriority
import com.jarvis.assistant.data.model.TaskContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class TaskContextEngine(private val context: Context) {

    private val db = AppDatabase.get(context).taskContextDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentTask = MutableStateFlow(TaskContext())
    val currentTask: StateFlow<TaskContext> = _currentTask.asStateFlow()

    init {
        scope.launch {
            try {
                val record = db.getContext("default_task")
                if (record != null) {
                    val completed = try { JarvisJson.decodeFromString<List<String>>(record.completedStepsJson) } catch (_: Exception) { emptyList() }
                    val pending = try { JarvisJson.decodeFromString<List<String>>(record.pendingStepsJson) } catch (_: Exception) { emptyList() }
                    _currentTask.value = TaskContext(
                        id = record.id,
                        objective = record.objective,
                        category = record.category,
                        targetPackage = record.targetPackage,
                        completedSteps = completed,
                        pendingSteps = pending,
                        lastUpdated = record.updatedAt
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun updateTaskFromUserCommand(prompt: String, activePkg: String?) {
        val t = prompt.lowercase().trim()
        val category = inferCategory(t, activePkg)
        val objective = if (t.length > 5 && !isGenericGreeting(t)) prompt.take(120) else _currentTask.value.objective

        val updatedCompleted = (_currentTask.value.completedSteps + prompt.take(80)).takeLast(10)
        val newTask = _currentTask.value.copy(
            objective = objective,
            category = category,
            targetPackage = activePkg ?: _currentTask.value.targetPackage,
            completedSteps = updatedCompleted,
            lastUpdated = System.currentTimeMillis()
        )

        _currentTask.value = newTask
        persistTask(newTask)
    }

    fun updateTaskFromAppSwitch(activePkg: String) {
        if (activePkg.isBlank()) return
        val current = _currentTask.value
        // Do not reset task context on simple app switches, just update active pkg
        val updated = current.copy(
            targetPackage = activePkg,
            lastUpdated = System.currentTimeMillis()
        )
        _currentTask.value = updated
        persistTask(updated)
    }

    fun clearTaskMemory() {
        val reset = TaskContext()
        _currentTask.value = reset
        scope.launch {
            try { db.clearTaskContexts() } catch (_: Exception) {}
        }
    }

    /** App-relative offline suggestion rules (Spotify, YouTube, WhatsApp, Dev, etc.) */
    fun getAppRelativeFallback(activePkg: String?): ContextSuggestion? {
        val pkg = activePkg?.lowercase() ?: return null
        return when {
            pkg.contains("spotify") -> ContextSuggestion(
                title = "Sleep Timer & Audio Control",
                detail = "You are listening to music on Spotify. Would you like me to set a 30-minute sleep timer?",
                command = "Set a 30 minute sleep timer for Spotify",
                priority = SuggestionPriority.MEDIUM,
                category = "media",
                targetApp = "Spotify"
            )
            pkg.contains("youtube") -> ContextSuggestion(
                title = "Related Content & Playback",
                detail = "You are on YouTube. Would you like me to find related tutorials or switch to full screen?",
                command = "Search related tutorials on YouTube",
                priority = SuggestionPriority.MEDIUM,
                category = "media",
                targetApp = "YouTube"
            )
            pkg.contains("whatsapp") -> ContextSuggestion(
                title = "Read Unread Notifications",
                detail = "You have WhatsApp active. Would you like me to summarize recent unread messages?",
                command = "Read out unread WhatsApp notifications",
                priority = SuggestionPriority.MEDIUM,
                category = "messaging",
                targetApp = "WhatsApp"
            )
            pkg.contains("chrome") || pkg.contains("browser") -> ContextSuggestion(
                title = "Web Search & Assistance",
                detail = "Would you like me to summarize the current webpage or search related articles?",
                command = "Summarize this topic",
                priority = SuggestionPriority.LOW,
                category = "browser",
                targetApp = "Chrome"
            )
            else -> null
        }
    }

    private fun inferCategory(text: String, pkg: String?): String = when {
        text.contains("android") || text.contains("code") || text.contains("ui") || text.contains("gradle") -> "software_development"
        text.contains("music") || text.contains("song") || text.contains("play") || pkg?.contains("spotify") == true -> "media"
        text.contains("video") || text.contains("movie") || pkg?.contains("youtube") == true -> "media"
        text.contains("message") || text.contains("chat") || pkg?.contains("whatsapp") == true -> "messaging"
        text.contains("learn") || text.contains("python") || text.contains("course") -> "learning"
        text.contains("navigate") || text.contains("map") || text.contains("traffic") -> "travel"
        else -> _currentTask.value.category
    }

    private fun isGenericGreeting(t: String): Boolean =
        t == "hi" || t == "hii" || t == "hello" || t == "hey" || t == "jarvis" || t.startsWith("hey jarvis")

    private fun persistTask(task: TaskContext) {
        scope.launch {
            try {
                val entity = TaskContextRecordEntity(
                    id = task.id,
                    objective = task.objective,
                    category = task.category,
                    targetPackage = task.targetPackage,
                    completedStepsJson = JarvisJson.encodeToString(task.completedSteps),
                    pendingStepsJson = JarvisJson.encodeToString(task.pendingSteps),
                    updatedAt = task.lastUpdated
                )
                db.saveContext(entity)
            } catch (_: Exception) {}
        }
    }
}
