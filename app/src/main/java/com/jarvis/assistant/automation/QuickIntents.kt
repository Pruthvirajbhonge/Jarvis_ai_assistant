package com.jarvis.assistant.automation

import com.jarvis.assistant.data.model.PhoneAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Instant, rule-based commands: no AI call, no internet, answered in milliseconds.
 * Returns null when the sentence is not one of these (then the AI decides).
 * Volume / torch / call / open-close app are already handled earlier by PhoneHardwareExecutor.
 */
object QuickIntents {

    sealed class Result {
        data class Say(val text: String) : Result()
        data class Run(val action: PhoneAction) : Result()
    }

    private val NOISE = Regex("\\b(please|jarvis|hey|ok|okay|sir|boss|can you|could you|will you|now|for me|the|a|tell me|what|whats|is|s)\\b")

    private fun clean(raw: String): String = raw.lowercase()
        .replace(Regex("[^a-z0-9\\u0900-\\u097F ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun act(name: String, vararg kv: Pair<String, String>) =
        PhoneAction(name, buildJsonObject { for ((k, v) in kv) put(k, v) })

    private val R_HOME = Regex("^(go )?(to )?(the )?home( screen)?$")
    private val R_BACK = Regex("^(go )?back$")
    private val R_RECENTS = Regex("^(show |open )?(the )?(recents?|recent apps?)$")
    private val R_SHOT = Regex("^(take )?(a )?screen ?shot$")
    private val R_LOCK = Regex("^lock( the)?( screen| phone)?$")
    private val R_NOTIF = Regex("^(open |show |pull down )?(the )?notifications?( panel| shade| bar)?$")
    private val R_QS = Regex("^(open |show )?quick settings$")
    private val R_PAUSE = Regex("^pause( the)?( music| song| video| playback)?$")
    private val R_PLAY = Regex("^(play|resume)( the)?( music| song| video| playback)?$")
    private val R_NEXT = Regex("^(next|skip)( the)?( song| track)?$")
    private val R_PREV = Regex("^(previous|go to previous)( the)?( song| track)?$")
    private val R_STOPM = Regex("^stop( the)?( music| song| playing| playback)$")
    private val R_SETTINGS = Regex("^(open )?(wifi|wi fi|bluetooth|display|sound|battery|location|airplane|nfc|storage|accessibility) settings$")
    private val OTHER_TIME = Regex("\\b(set|timer|alarm|remind|zone|for|in|at|until|from|every)\\b")

    private val MEDIA_NOUN_LATIN = Regex("\\b(music|song|songs|video|audio|track|playback|playing|gaana|gana|gaane|gani)\\b")
    private val MEDIA_NOUN_DEV = listOf("गाणे", "गाणं", "गाणी", "गाना", "संगीत", "म्युझिक", "म्यूजिक")
    private val STOP_LATIN = Regex("\\b(stop|pause|band|ruk|ruko|thamba)\\b")
    private val STOP_DEV = listOf("बंद", "बन्द", "थांब", "रोक", "रुक")
    private val APP_NAMES = Regex("\\b(youtube|spotify|whatsapp|chrome|instagram|app)\\b")

    /** "stop song", "pause the music", "gaana band kara", "गाणं बंद कर" ... = stop the SOUND, never close an app. */
    fun isMediaStop(raw: String): Boolean {
        val t = clean(raw)
        if (t.isEmpty() || t.split(' ').size > 7) return false
        val noun = MEDIA_NOUN_LATIN.containsMatchIn(t) || MEDIA_NOUN_DEV.any { t.contains(it) }
        val verb = STOP_LATIN.containsMatchIn(t) || STOP_DEV.any { t.contains(it) }
        return noun && verb && !APP_NAMES.containsMatchIn(t)
    }

    fun match(raw: String, batteryPercent: () -> Int?): Result? {
        val t = clean(raw)
        if (t.isEmpty()) return null
        // PAUSE works in every player (KEYCODE_MEDIA_STOP is ignored by many apps)
        if (isMediaStop(raw)) return Result.Run(act("media", "command" to "pause"))
        val words = t.split(' ')
        val n = t.replace(NOISE, " ").replace(Regex("\\s+"), " ").trim() // "what is the time" -> "time"

        // ---- answers (instant) ----
        if (words.size <= 6 && !OTHER_TIME.containsMatchIn(t)) {
            if (n == "time" || n == "current time" || n == "time now" || n == "kiti vajle" || n == "किती वाजले") {
                val s = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                return Result.Say("It's $s, sir.")
            }
            if (n == "date" || n == "today date" || n == "date today" || n == "day today" || n == "today") {
                val s = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
                return Result.Say("Today is $s, sir.")
            }
            if (n == "battery" || n == "battery level" || n == "battery percentage" || n == "battery status") {
                val p = batteryPercent()
                return Result.Say(if (p != null) "Battery is at $p percent, sir." else "I can't read the battery right now, sir.")
            }
        }

        // ---- phone actions (instant) ----
        return when {
            R_PAUSE.matches(t) -> Result.Run(act("media", "command" to "pause"))
            R_PLAY.matches(t) -> Result.Run(act("media", "command" to "play"))
            R_NEXT.matches(t) -> Result.Run(act("media", "command" to "next"))
            R_PREV.matches(t) -> Result.Run(act("media", "command" to "previous"))
            R_STOPM.matches(t) -> Result.Run(act("media", "command" to "stop"))
            R_HOME.matches(t) -> Result.Run(act("home"))
            R_BACK.matches(t) -> Result.Run(act("back"))
            R_RECENTS.matches(t) -> Result.Run(act("recents"))
            R_SHOT.matches(t) -> Result.Run(act("screenshot"))
            R_LOCK.matches(t) -> Result.Run(act("lock_screen"))
            R_NOTIF.matches(t) -> Result.Run(act("notifications"))
            R_QS.matches(t) -> Result.Run(act("quick_settings"))
            R_SETTINGS.matches(t) -> {
                val page = R_SETTINGS.find(t)!!.groupValues[2].replace("wi fi", "wifi")
                Result.Run(act("settings", "page" to page))
            }
            else -> null
        }
    }
}
