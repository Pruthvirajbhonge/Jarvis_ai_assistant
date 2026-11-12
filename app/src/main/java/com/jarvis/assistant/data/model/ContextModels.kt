package com.jarvis.assistant.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SuggestionPriority {
    HIGH, MEDIUM, LOW, NONE;

    companion object {
        fun fromString(value: String?): SuggestionPriority = when (value?.uppercase()?.trim()) {
            "HIGH" -> HIGH
            "MEDIUM" -> MEDIUM
            "LOW" -> LOW
            else -> NONE
        }
    }
}

@Serializable
data class ContextSuggestion(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val detail: String = "",
    val command: String = "",
    val priority: SuggestionPriority = SuggestionPriority.NONE,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "general",
    val targetApp: String? = null
) {
    val isValid: Boolean
        get() = title.isNotBlank() && detail.isNotBlank() && command.isNotBlank() && priority != SuggestionPriority.NONE
}

@Serializable
data class TaskContext(
    val id: String = "default_task",
    val objective: String = "General Assistant Assistance",
    val category: String = "general",
    val targetPackage: String? = null,
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)
