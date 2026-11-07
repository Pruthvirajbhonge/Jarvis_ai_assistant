package com.jarvis.assistant.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.jarvisDataStore by preferencesDataStore(name = "jarvis_settings")

/** Old defaults saved by earlier versions (they return 404 / are retired). They are replaced by the new defaults. */
private val OLD_GEMINI_MODELS = setOf(
    "gemini-1.5-flash", "gemini-1.5-flash-8b", "gemini-1.5-pro",
    "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.5-flash"
)
private val OLD_GROQ_MODELS = setOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "llama3-70b-8192", "mixtral-8x7b-32768")

data class JarvisSettings(
    /** false = phone mode (default). true = commands go to the PC backend. */
    val pcMode: Boolean = false,
    val pcHost: String = DEFAULT_HOST,
    val token: String = "",

    val geminiKey: String = BuildConfig.GEMINI_API_KEY,
    val geminiModel: String = DEFAULT_GEMINI,
    val groqKey: String = BuildConfig.GROQ_API_KEY,
    val groqModel: String = DEFAULT_GROQ,
    val xaiKey: String = BuildConfig.XAI_API_KEY,
    val xaiModel: String = DEFAULT_XAI,
    val openRouterKey: String = BuildConfig.OPENROUTER_API_KEY,
    val openRouterModel: String = DEFAULT_OPENROUTER,

    val wakeWordEnabled: Boolean = true,
    val floatingOrb: Boolean = true
) {
    val hasAnyAi: Boolean
        get() = listOf(geminiKey, groqKey, xaiKey, openRouterKey).any { it.isNotBlank() }

    /** Normalised "ip:port". Fixes "http://", trailing slashes and the "192.168.x.x/8000" typo. */
    val hostPort: String
        get() {
            var h = pcHost.trim()
                .removePrefix("http://").removePrefix("https://")
                .removePrefix("ws://").removePrefix("wss://")
                .trimEnd('/')
            h = h.replace(Regex("/(\\d{2,5})$"), ":$1")
            h = h.substringBefore('/')
            if (!h.contains(':')) h += ":8000"
            return h
        }

    fun httpUrl(path: String) = "http://$hostPort/${path.trimStart('/')}"
    fun wsUrl(path: String) = "ws://$hostPort/${path.trimStart('/')}"
    fun authHeader(): String? = token.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

    companion object {
        const val DEFAULT_HOST = "192.168.43.59:8000"
        const val DEFAULT_GEMINI = "gemini-3.5-flash-lite"
        const val DEFAULT_GROQ = "openai/gpt-oss-120b"
        const val DEFAULT_XAI = "grok-2-latest"
        const val DEFAULT_OPENROUTER = "openrouter/auto"
    }
}

class SettingsStore(context: Context) {
    private val store = context.applicationContext.jarvisDataStore

    val flow: Flow<JarvisSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            val d = JarvisSettings()
            fun str(k: androidx.datastore.preferences.core.Preferences.Key<String>, def: String) =
                p[k]?.takeIf { it.isNotBlank() } ?: def
            JarvisSettings(
                pcMode = p[PC_MODE] ?: false,
                pcHost = str(HOST, d.pcHost),
                token = p[TOKEN] ?: "",
                geminiKey = str(GEMINI_KEY, d.geminiKey),
                geminiModel = str(GEMINI_MODEL, d.geminiModel).takeUnless { it.lowercase() in OLD_GEMINI_MODELS } ?: d.geminiModel,
                groqKey = str(GROQ_KEY, d.groqKey),
                groqModel = str(GROQ_MODEL, d.groqModel).takeUnless { it.lowercase() in OLD_GROQ_MODELS } ?: d.groqModel,
                xaiKey = str(XAI_KEY, d.xaiKey),
                xaiModel = str(XAI_MODEL, d.xaiModel),
                openRouterKey = str(OR_KEY, d.openRouterKey),
                openRouterModel = str(OR_MODEL, d.openRouterModel),
                wakeWordEnabled = p[WAKE] ?: d.wakeWordEnabled,
                floatingOrb = p[ORB] ?: d.floatingOrb
            )
        }

    suspend fun current(): JarvisSettings = flow.first()

    suspend fun setPcMode(on: Boolean) {
        store.edit { it[PC_MODE] = on }
    }

    /** Saves everything except [JarvisSettings.pcMode], which has its own switch. */
    suspend fun save(s: JarvisSettings) {
        store.edit { p ->
            p[HOST] = s.pcHost.trim()
            p[TOKEN] = s.token.trim()
            p[GEMINI_KEY] = s.geminiKey.trim()
            p[GEMINI_MODEL] = s.geminiModel.trim().ifEmpty { JarvisSettings.DEFAULT_GEMINI }
            p[GROQ_KEY] = s.groqKey.trim()
            p[GROQ_MODEL] = s.groqModel.trim().ifEmpty { JarvisSettings.DEFAULT_GROQ }
            p[XAI_KEY] = s.xaiKey.trim()
            p[XAI_MODEL] = s.xaiModel.trim().ifEmpty { JarvisSettings.DEFAULT_XAI }
            p[OR_KEY] = s.openRouterKey.trim()
            p[OR_MODEL] = s.openRouterModel.trim().ifEmpty { JarvisSettings.DEFAULT_OPENROUTER }
            p[WAKE] = s.wakeWordEnabled
            p[ORB] = s.floatingOrb
        }
    }

    private companion object {
        val PC_MODE = booleanPreferencesKey("pc_mode")
        val HOST = stringPreferencesKey("pc_host")
        val TOKEN = stringPreferencesKey("token")
        val GEMINI_KEY = stringPreferencesKey("gemini_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GROQ_KEY = stringPreferencesKey("groq_key")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val XAI_KEY = stringPreferencesKey("xai_key")
        val XAI_MODEL = stringPreferencesKey("xai_model")
        val OR_KEY = stringPreferencesKey("openrouter_key")
        val OR_MODEL = stringPreferencesKey("openrouter_model")
        val WAKE = booleanPreferencesKey("wake_enabled")
        val ORB = booleanPreferencesKey("orb_enabled")
    }
}
