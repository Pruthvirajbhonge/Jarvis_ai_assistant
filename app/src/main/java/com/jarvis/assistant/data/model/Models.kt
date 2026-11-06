package com.jarvis.assistant.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** Tolerant JSON: unknown keys are ignored, defaults are always sent. */
val JarvisJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
data class CommandRequest(
    val prompt: String,
    val device: String = "pc",
    @SerialName("client_speaks") val clientSpeaks: Boolean = true
)

@Serializable
data class CommandResponse(
    val reply: String = "",
    /** "phone" means the backend wants the phone to execute [action] locally. */
    val target: String? = null,
    val action: JsonElement? = null,
    val params: JsonElement? = null
) {
    fun toPhoneAction(): PhoneAction? = parsePhoneAction(action, params)
}

data class PhoneAction(
    val action: String,
    val params: JsonObject = JsonObject(emptyMap())
) {
    fun str(vararg keys: String): String? = params.str(*keys)
}

data class PhoneTask(val id: String, val action: PhoneAction, val reply: String)

data class Metrics(val cpu: Int?, val ram: Int?, val disk: Int?)

/** Accepts both {"action":"open_app","params":{...}} and {"action":{"name":"open_app","params":{...}}}. */
fun parsePhoneAction(action: JsonElement?, params: JsonElement?): PhoneAction? {
    val topParams = params as? JsonObject ?: JsonObject(emptyMap())
    return when (action) {
        is JsonObject -> {
            val name = action.str("name", "action", "type") ?: return null
            PhoneAction(name, (action["params"] as? JsonObject) ?: topParams)
        }
        is JsonPrimitive -> action.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("none", true) }
            ?.let { PhoneAction(it, topParams) }
        else -> null
    }
}

fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = this[k]
        if (v is JsonPrimitive && v !is JsonNull) {
            v.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return null
}

/** Finds a percentage value under any of [keys], searching nested objects. */
fun JsonElement.findPercent(vararg keys: String): Int? {
    if (this !is JsonObject) return null
    for (k in keys) {
        val v = this[k] ?: continue
        (v as? JsonPrimitive)?.doubleOrNull?.let { return it.roundToInt() }
        if (v is JsonObject) v.findPercent("percent", "used_percent", "usage")?.let { return it }
    }
    for (child in values) child.findPercent(*keys)?.let { return it }
    return null
}

fun JsonElement.toMetrics(): Metrics? {
    val cpu = findPercent("cpu", "cpu_percent", "cpu_usage")
    val ram = findPercent("ram", "ram_percent", "memory", "memory_percent", "mem")
    val disk = findPercent("disk", "disk_percent", "disk_usage")
    return if (cpu == null && ram == null && disk == null) null else Metrics(cpu, ram, disk)
}
