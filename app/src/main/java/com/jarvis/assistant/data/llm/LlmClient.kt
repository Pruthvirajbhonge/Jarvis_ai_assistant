package com.jarvis.assistant.data.llm

import com.jarvis.assistant.data.model.ContextSuggestion
import com.jarvis.assistant.data.model.JarvisJson
import com.jarvis.assistant.data.model.PhoneAction
import com.jarvis.assistant.data.model.SuggestionPriority
import com.jarvis.assistant.data.model.parsePhoneAction
import com.jarvis.assistant.data.model.str
import com.jarvis.assistant.data.prefs.JarvisSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class ChatMsg(val role: String, val text: String) // role: "user" | "assistant"
data class ModelOutput(val reply: String, val action: PhoneAction?)
data class ChainResult(val text: String, val provider: String, val errors: List<String>)

class ProviderException(
    message: String,
    val code: Int = 0,
    val fatal: Boolean = false,
    val modelMissing: Boolean = false
) : IOException(message)

class AllProvidersFailed(val errors: List<String>) : IOException(errors.joinToString(" | "))

interface LlmProvider {
    val name: String
    suspend fun complete(system: String, history: List<ChatMsg>, user: String): String
}

private const val GEMINI_VERIFIED = "gemini-3.5-flash-lite"

/** Fixes typos only. (It no longer rewrites valid model names into retired ones.) */
private fun cleanModelName(name: String): String =
    name.trim().lowercase().replace(" ", "-").replace("vercatile", "versatile")

/** gemini-1.5-* and gemini-2.0-* return 404, so they are never sent. */
private fun usableGeminiModel(name: String): String {
    val m = cleanModelName(name)
    return if (m.isEmpty() || m.startsWith("gemini-1.5") || m.startsWith("gemini-2.0")) GEMINI_VERIFIED else m
}

// ---- shared HTTP ----------------------------------------------------------------------------

/** Turns an error response into a short, readable reason (e.g. "HTTP 400: API key not valid"). */
internal fun httpError(code: Int, body: String): ProviderException {
    val parsed = try { JarvisJson.parseToJsonElement(body) } catch (e: Exception) { null }
    val root = (parsed as? JsonObject) ?: (parsed as? JsonArray)?.firstOrNull() as? JsonObject
    val msg = when (val err = root?.get("error")) {
        is JsonObject -> err.str("message")
        is JsonPrimitive -> err.contentOrNull
        else -> null
    } ?: body
    val short = msg.replace('\n', ' ').trim().take(140).ifEmpty { "no details" }
    val isFatal = code == 401 || code == 403 || (code == 400 && short.contains("api key", true))
    val isModelMissing = code == 404 ||
        short.contains("not found", true) ||
        short.contains("no longer available", true) ||
        short.contains("not supported", true) ||
        short.contains("decommissioned", true) ||
        short.contains("deprecated", true) ||
        short.contains("does not exist", true)
    return ProviderException(
        message = "HTTP $code: $short",
        code = code,
        fatal = isFatal,
        modelMissing = isModelMissing
    )
}

internal suspend fun OkHttpClient.postJson(url: String, headers: Map<String, String>, body: String): String =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp.code, raw)
            raw
        }
    }

// ---- providers --------------------------------------------------------------------------------

/** Google Gemini. Automatically tries newer model names if the configured one is retired/missing/rate-limited/overloaded. */
class GeminiProvider(
    private val client: OkHttpClient,
    private val key: String,
    model: String
) : LlmProvider {
    override val name = "Gemini"

    // Configured model first, then the verified one, then Google's "latest lite" alias.
    private val models = listOf(usableGeminiModel(model), GEMINI_VERIFIED, "gemini-flash-lite-latest").distinct()

    override suspend fun complete(system: String, history: List<ChatMsg>, user: String): String {
        var last: ProviderException? = null
        for (m in models) {
            try {
                return call(m, system, history, user)
            } catch (e: ProviderException) {
                last = e
                if (e.fatal) throw e // key/auth problems: don't retry other models
            }
        }
        throw last ?: ProviderException("No Gemini model available")
    }

    private suspend fun call(model: String, system: String, history: List<ChatMsg>, user: String): String {
        fun turn(role: String, text: String) = buildJsonObject {
            put("role", role)
            put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        }
        val body = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            put("contents", buildJsonArray {
                history.forEach { add(turn(if (it.role == "assistant") "model" else "user", it.text)) }
                add(turn("user", user))
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("temperature", 0.6)
            })
        }.toString()

        val raw = client.postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to key),
            body
        )
        val text = JarvisJson.parseToJsonElement(raw).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (text.isNullOrBlank()) throw ProviderException("Empty reply (blocked or no content)")
        return text
    }
}

/** Groq, xAI Grok, OpenRouter, OpenAI... anything that speaks the OpenAI /chat/completions format. */
class OpenAiCompatProvider(
    override val name: String,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val key: String,
    primaryModel: String,
    fallbacks: List<String> = emptyList(),
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmProvider {

    private val models = (listOf(cleanModelName(primaryModel)) + fallbacks.map { cleanModelName(it) })
        .filter { it.isNotEmpty() }
        .distinct()

    override suspend fun complete(system: String, history: List<ChatMsg>, user: String): String {
        var last: ProviderException? = null
        for (m in models) {
            try {
                return call(m, system, history, user)
            } catch (e: ProviderException) {
                last = e
                if (e.fatal) throw e
            }
        }
        throw last ?: ProviderException("No $name model available")
    }

    private suspend fun call(model: String, system: String, history: List<ChatMsg>, user: String): String {
        fun msg(role: String, content: String) = buildJsonObject {
            put("role", role)
            put("content", content)
        }
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.6)
            put("messages", buildJsonArray {
                add(msg("system", system))
                history.forEach { add(msg(it.role, it.text)) }
                add(msg("user", user))
            })
        }.toString()

        val raw = client.postJson(
            "$baseUrl/chat/completions",
            mapOf("Authorization" to "Bearer $key") + extraHeaders,
            body
        )
        val text = JarvisJson.parseToJsonElement(raw).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
        if (text.isNullOrBlank()) throw ProviderException("Empty reply")
        return text
    }
}

/** Providers in priority order, only those that have a key. */
fun JarvisSettings.buildProviders(client: OkHttpClient): List<LlmProvider> = buildList {
    if (geminiKey.isNotBlank()) add(GeminiProvider(client, geminiKey.trim(), geminiModel))
    if (groqKey.isNotBlank()) {
        add(OpenAiCompatProvider(
            "Groq", client, "https://api.groq.com/openai/v1", groqKey.trim(),
            groqModel.trim().ifEmpty { JarvisSettings.DEFAULT_GROQ },
            listOf("openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        ))
    }
    if (xaiKey.isNotBlank()) {
        add(OpenAiCompatProvider(
            "Grok", client, "https://api.x.ai/v1", xaiKey.trim(),
            xaiModel.trim().ifEmpty { JarvisSettings.DEFAULT_XAI },
            listOf("grok-2-latest", "grok-beta", "grok-2")
        ))
    }
    if (openRouterKey.isNotBlank()) {
        add(OpenAiCompatProvider(
            "OpenRouter", client, "https://openrouter.ai/api/v1", openRouterKey.trim(),
            openRouterModel.trim().ifEmpty { JarvisSettings.DEFAULT_OPENROUTER },
            listOf("openrouter/auto", "google/gemini-2.0-flash-lite-001", "meta-llama/llama-3.3-70b-instruct"),
            mapOf("HTTP-Referer" to "https://jarvis.local", "X-Title" to "JARVIS Android")
        ))
    }
}

/**
 * Tries providers in order. A provider that just failed is skipped for a while
 * (60 s for rate limits/outages, 10 min for bad keys) so the next command is fast.
 */
class LlmChain {
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    suspend fun complete(providers: List<LlmProvider>, system: String, history: List<ChatMsg>, user: String): ChainResult {
        val now = System.currentTimeMillis()
        val ready = providers.filter { (cooldownUntil[it.name] ?: 0L) <= now }.ifEmpty { providers }
        val errors = mutableListOf<String>()
        for (p in ready) {
            try {
                val text = p.complete(system, history, user)
                cooldownUntil.remove(p.name)
                return ChainResult(text, p.name, errors)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                errors += "${p.name}: ${e.message}"
                cooldownUntil[p.name] = now + if (e.fatal) 600_000L else 60_000L
            } catch (e: Exception) {
                errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}"
                cooldownUntil[p.name] = now + 30_000L
            }
        }
        throw AllProvidersFailed(errors)
    }
}

// ---- reply parsing ----------------------------------------------------------------------------

fun extractJsonObject(text: String): JsonObject? {
    val s = text.indexOf('{')
    val e = text.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return try { JarvisJson.parseToJsonElement(text.substring(s, e + 1)) as? JsonObject } catch (ex: Exception) { null }
}

fun parseModelOutput(text: String): ModelOutput {
    val obj = extractJsonObject(text) ?: return ModelOutput(text.trim(), null)
    return ModelOutput(
        reply = obj.str("reply", "speech", "text").orEmpty(),
        action = parsePhoneAction(obj["action"], obj["params"])
    )
}

object Prompts {
    val PHONE_ASSISTANT = """
        You are J.A.R.V.I.S., an AI assistant living on the user's Android phone.
        Understand English, Marathi, Hindi and Romanized Marathi/Hindi. Reply in the user's language,
        in a polite butler tone (you may say "sir").

        Response Guidelines:
        1. FOR SIMPLE DIRECT ACTIONS (open_app, call, volume, torch, settings, media, etc.):
           Reply with a concise, clear verbal confirmation (e.g. "Opening WhatsApp for you, sir.")
           Do NOT add extra unsolicited chatter or suggestions for simple commands.
        2. FOR COMPLEX QUESTIONS, PLANNING, IDEAS, OR MULTI-STEP TASKS:
           Be a helpful, intelligent companion: provide clear answers and offer 1-2 relevant,
           contextual suggestions or next steps.

        ALWAYS answer with ONE JSON object and nothing else:
          {"reply": "<short spoken text>", "action": null}
        or, when the phone must do something:
          {"reply": "<short spoken text>", "action": {"name": "<action>", "params": {...}}}
        Actions:
          open_app {"app":"Instagram"}            close_app {"app":"WhatsApp"}
          call {"name":"Mom"} or {"number":"98..."}
          sms {"name"|"number","message"}         whatsapp {"name"|"number","message"}
          email {"to","subject","body"}           alarm {"hour":0-23,"minutes":0-59,"message"}
          timer {"minutes"|"seconds"}             web_search {"query"}      open_url {"url"}
          youtube {"query"}                       navigate {"destination"}
          media {"command":"play|pause|next|previous|stop"}
          volume {"direction":"up|down|mute|unmute"} or {"level":0-100}
          brightness {"level":0-100}              torch {"state":"on|off"}   camera {}
          settings {"page":"wifi|bluetooth|display|sound|battery|location|apps|airplane|nfc|storage|accessibility|date"}
            (opens that settings page; Android does not allow silently toggling Wi-Fi/Bluetooth)
          screenshot {}  lock_screen {}  notifications {}  quick_settings {}  back {}  home {}  recents {}
          scroll {"direction":"up|down"}          click {"text":"label"}     type {"text":"..."}
          ui_task {"goal":"..."}  = a multi-step job inside ANY app, done by reading the screen and tapping,
            e.g. "search running shoes on Amazon and open the first result", "turn on dark mode in Instagram".
        Rules: prefer a direct action over ui_task; use null action for normal conversation, questions,
        time/battery/notification questions (use the phone context you are given). Never invent actions.
        Never pay, buy, delete data or send money on your own: ask the user to confirm first.
    """.trimIndent()

    val UI_AGENT = """
        You operate an Android phone screen to achieve a goal, ONE step at a time.
        You get the goal, your previous steps, and a numbered list of what is on screen:
          [index] ClassName "label" flags(clickable, editable, scrollable, checked)
        Answer with ONE JSON object and nothing else:
          {"action": "<action>", "params": {...}, "say": "<only for done/fail>"}
        Actions: click_index {"index":N}   type {"index":N,"text":"..."}   scroll {"direction":"up|down"}
                 back {}   home {}   open_app {"app":"Name"}   wait {}
                 done {} (goal achieved)   fail {} (impossible or blocked)
        Rules: pick the single best next step; after type, press the search/send/enter button with click_index;
        use scroll when the target is not visible; do not repeat a step that did not work, try another way.
        NEVER complete payments, purchases, deletions or money transfers: use fail and say the user must confirm.
        Keep "say" short and in the user's language.
    """.trimIndent()

    val PROACTIVE_SUGGESTION = """
        You are J.A.R.V.I.S., an intelligent personal AI assistant evaluating the user's current Android context.
        Understand English, Marathi, Hindi and Romanized Marathi/Hindi.

        Goal: Identify if there is a HIGH-VALUE, helpful proactive next step for the user's ongoing task or active application.
        Do NOT generate random or annoying suggestions. If no clear, valuable suggestion exists, return priority "none".

        Response Format:
        Respond ONLY with ONE JSON object matching this structure:
        {
          "title": "<Short 2-4 word title>",
          "detail": "<1 sentence clear explanation>",
          "command": "<The exact phone voice command to run if accepted>",
          "priority": "high" | "medium" | "low" | "none",
          "category": "<general|software_development|media|messaging|learning|travel>",
          "targetApp": "<App Name or null>"
        }

        Examples:
        - If task is Android development & user is on UI layout:
          {"title":"Next Development Step","detail":"Your Android UI is ready. Would you like to add animations and transitions?","command":"Help me add animations and transitions to the UI layout.","priority":"medium","category":"software_development","targetApp":"Android Studio"}
        - If active app is Spotify:
          {"title":"Sleep Timer & Volume","detail":"You are listening to Spotify. Would you like to set a 30-minute sleep timer?","command":"Set a 30 minute sleep timer for Spotify","priority":"medium","category":"media","targetApp":"Spotify"}
        - If user switched to Instagram during a coding task:
          {"title":"","detail":"","command":"","priority":"none","category":"general","targetApp":null}

        Rules:
        - Never invent actions or payments.
        - Command must be an actionable phrase the assistant can process.
        - Respect the user's preferred language.
    """.trimIndent()
}

fun parseSuggestionOutput(text: String): ContextSuggestion? {
    val obj = extractJsonObject(text) ?: return null
    val title = obj.str("title").orEmpty()
    val detail = obj.str("detail").orEmpty()
    val command = obj.str("command").orEmpty()
    val priorityStr = obj.str("priority").orEmpty()
    val category = obj.str("category") ?: "general"
    val targetApp = obj.str("targetApp", "app")

    val priority = com.jarvis.assistant.data.model.SuggestionPriority.fromString(priorityStr)
    if (priority == com.jarvis.assistant.data.model.SuggestionPriority.NONE || title.isBlank() || detail.isBlank() || command.isBlank()) {
        return null
    }

    return com.jarvis.assistant.data.model.ContextSuggestion(
        title = title,
        detail = detail,
        command = command,
        priority = priority,
        category = category,
        targetApp = targetApp
    )
}
