package com.jarvis.assistant.automation

import com.jarvis.assistant.data.llm.Prompts
import com.jarvis.assistant.data.llm.extractJsonObject
import com.jarvis.assistant.data.model.PhoneAction
import com.jarvis.assistant.data.model.str
import com.jarvis.assistant.data.repo.JarvisRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Lets the assistant use ANY app: read the screen (via the Accessibility service), ask the AI for
 * the next tap/type/scroll, run it, repeat. The screen text is only sent to the AI during these tasks.
 */
class UiAgent(
    private val executor: PhoneActionExecutor,
    private val repository: JarvisRepository,
    private val onProgress: (String) -> Unit
) {
    suspend fun run(goal: String): String {
        if (goal.isBlank()) return "What should I do, sir?"
        if (JarvisAccessibilityService.instance == null) {
            return "Please enable the Accessibility service in settings first."
        }

        val history = mutableListOf<String>()
        for (step in 1..MAX_STEPS) {
            delay(if (step == 1) 1800L else 1100L) // let the previous screen settle
            val service = JarvisAccessibilityService.instance ?: return "The Accessibility service stopped."
            val screen = service.describeScreen()
            onProgress("Step $step/$MAX_STEPS…")

            val raw = try {
                repository.askRaw(
                    Prompts.UI_AGENT,
                    "Goal: $goal\nPrevious steps:\n${history.joinToString("\n").ifBlank { "(none)" }}\n\nCurrent screen:\n$screen"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return "I lost the AI connection during the task: ${e.message}"
            }

            val obj = extractJsonObject(raw) ?: return "I couldn't understand the AI's next step."
            val action = obj.str("action", "name")?.lowercase() ?: return "I couldn't understand the AI's next step."
            val say = obj.str("say", "reply").orEmpty()
            when (action) {
                "done" -> return say.ifBlank { "Done, sir." }
                "fail" -> return say.ifBlank { "I couldn't finish that, sir." }
            }
            val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
            val result = executor.execute(PhoneAction(action, params))
            history += "$step. $action $params -> $result"
        }
        return "I stopped after $MAX_STEPS steps, sir. The task may be incomplete."
    }

    private companion object {
        const val MAX_STEPS = 10
    }
}
