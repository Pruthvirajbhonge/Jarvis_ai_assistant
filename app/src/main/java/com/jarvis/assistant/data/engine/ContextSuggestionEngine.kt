package com.jarvis.assistant.data.engine

import com.jarvis.assistant.data.model.ContextSuggestion
import com.jarvis.assistant.data.model.TaskContext
import com.jarvis.assistant.data.repo.JarvisRepository

class ContextSuggestionEngine(
    private val taskEngine: TaskContextEngine,
    private val repository: JarvisRepository,
    private val phoneContextSupplier: () -> String
) {
    suspend fun generateSuggestion(activePkg: String?): ContextSuggestion? {
        val currentTask = taskEngine.currentTask.value
        val phoneCtx = phoneContextSupplier()

        val compactPrompt = buildString {
            append("Current Objective: ").append(currentTask.objective).append("\n")
            append("Task Category: ").append(currentTask.category).append("\n")
            append("Foreground App Package: ").append(activePkg ?: currentTask.targetPackage ?: "none").append("\n")
            append("Completed Steps:\n").append(currentTask.completedSteps.joinToString("\n").ifBlank { "(none)" }).append("\n")
            append("\nPhone Status & Notifications:\n").append(phoneCtx)
        }

        val aiResult = try {
            repository.evaluateSuggestion(compactPrompt)
        } catch (_: Exception) {
            null
        }

        return if (aiResult != null && aiResult.isValid) {
            aiResult
        } else {
            taskEngine.getAppRelativeFallback(activePkg)
        }
    }
}
