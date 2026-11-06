package com.jarvis.assistant.data.repo

import com.jarvis.assistant.data.api.ApiConfig
import com.jarvis.assistant.data.api.JarvisApi
import com.jarvis.assistant.data.llm.AllProvidersFailed
import com.jarvis.assistant.data.llm.ChatMsg
import com.jarvis.assistant.data.llm.LlmChain
import com.jarvis.assistant.data.llm.Prompts
import com.jarvis.assistant.data.llm.buildProviders
import com.jarvis.assistant.data.llm.parseModelOutput
import com.jarvis.assistant.data.model.CommandRequest
import com.jarvis.assistant.data.model.JarvisJson
import com.jarvis.assistant.data.model.Metrics
import com.jarvis.assistant.data.model.PhoneAction
import com.jarvis.assistant.data.model.toMetrics
import com.jarvis.assistant.data.prefs.JarvisSettings
import com.jarvis.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Source { PC, PHONE, NONE }

data class AssistantResult(
    val reply: String,
    val action: PhoneAction?,
    val source: Source,
    val note: String? = null,
    val provider: String? = null
)

data class PcStatus(val reachable: Boolean, val metrics: Metrics?)

/**
 * Phone mode (default): AI providers with automatic failover (Gemini -> Groq -> Grok -> OpenRouter).
 * PC mode: the command goes to the Python backend instead.
 */
class JarvisRepository(
    private val settings: SettingsStore,
    http: OkHttpClient,
    private val phoneContext: () -> String
) {
    private val api: JarvisApi = Retrofit.Builder()
        .baseUrl("http://localhost/") // never used; every call passes a full @Url
        .client(http)
        .addConverterFactory(JarvisJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(JarvisApi::class.java)

    private val llmHttp = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS) // per provider, so a slow one fails over quickly
        .build()

    private val chain = LlmChain()
    private val history = ArrayDeque<ChatMsg>()

    suspend fun ask(prompt: String): AssistantResult {
        val s = settings.current()
        return if (s.pcMode) askPc(s, prompt) else askPhone(s, prompt)
    }

    // ---- phone mode -------------------------------------------------------------------------

    private suspend fun askPhone(s: JarvisSettings, prompt: String): AssistantResult {
        val providers = s.buildProviders(llmHttp)
        if (providers.isEmpty()) {
            return AssistantResult(
                "No AI key is set, sir. Please add a Gemini, Groq or Grok key in settings.",
                null, Source.NONE, "No AI key set"
            )
        }
        return try {
            val r = chain.complete(
                providers, Prompts.PHONE_ASSISTANT, historySnapshot(),
                "Phone context:\n${phoneContext()}\n\nUser: $prompt"
            )
            val out = parseModelOutput(r.text)
            remember(prompt, r.text)
            val note = if (r.errors.isEmpty()) null else "Switched to ${r.provider} (${r.errors.first()})".take(200)
            AssistantResult(out.reply, out.action, Source.PHONE, note, r.provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AllProvidersFailed) {
            AssistantResult(
                "All AI services failed, sir. Please check your keys and internet.",
                null, Source.NONE, e.message?.take(220)
            )
        } catch (e: Exception) {
            AssistantResult("AI error: ${e.message}", null, Source.NONE)
        }
    }

    /** One-shot call without chat history (used by the screen agent). Throws on failure. */
    suspend fun askRaw(system: String, user: String): String {
        val providers = settings.current().buildProviders(llmHttp)
        if (providers.isEmpty()) throw IOException("No AI key set")
        return chain.complete(providers, system, emptyList(), user).text
    }

    /** Pings every configured provider with the given (possibly unsaved) settings. */
    suspend fun testProviders(s: JarvisSettings): String {
        val providers = s.buildProviders(llmHttp)
        if (providers.isEmpty()) return "No API keys entered yet."
        val sb = StringBuilder()
        for (p in providers) {
            val t0 = System.currentTimeMillis()
            try {
                p.complete("Reply with the JSON {\"ok\":true}", emptyList(), "ping")
                sb.append("✓ ${p.name} — OK (${System.currentTimeMillis() - t0} ms)\n")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sb.append("✗ ${p.name} — ${e.message}\n")
            }
        }
        return sb.toString().trim()
    }

    private fun historySnapshot(): List<ChatMsg> = synchronized(history) { history.toList() }

    private fun remember(user: String, rawReply: String) {
        synchronized(history) {
            history.addLast(ChatMsg("user", user.take(400)))
            history.addLast(ChatMsg("assistant", rawReply.take(600)))
            while (history.size > 8) history.removeFirst()
        }
    }

    // ---- PC mode ----------------------------------------------------------------------------

    private suspend fun askPc(s: JarvisSettings, prompt: String): AssistantResult {
        val note: String = try {
            val resp = api.sendCommand(s.httpUrl(ApiConfig.COMMAND_PATH), s.authHeader(), CommandRequest(prompt))
            val local = if (resp.target.equals("phone", ignoreCase = true)) resp.toPhoneAction() else null
            return AssistantResult(resp.reply.ifBlank { "Done." }, local, Source.PC)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            "PC replied HTTP ${e.code()} for /${ApiConfig.COMMAND_PATH}"
        } catch (e: Exception) {
            "PC unreachable"
        }
        return AssistantResult(
            "I couldn't reach the PC, sir. Say 'phone mode' to switch back.",
            null, Source.NONE, note
        )
    }

    suspend fun pollStatus(): PcStatus = withContext(Dispatchers.IO) {
        val s = settings.current()
        try {
            val body = api.status(s.httpUrl(ApiConfig.STATUS_PATH), s.authHeader()).string()
            val metrics = try { JarvisJson.parseToJsonElement(body).toMetrics() } catch (e: Exception) { null }
            PcStatus(true, metrics)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            PcStatus(true, null) // server answered, so it is reachable
        } catch (e: Exception) {
            PcStatus(false, null)
        }
    }
}
