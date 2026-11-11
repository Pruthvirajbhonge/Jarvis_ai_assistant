package com.jarvis.assistant.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.data.model.PhoneAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Really changes the phone: volume (AudioManager), flashlight (CameraManager) and phone calls.
 *
 * Two entry points:
 *  - [tryHandleText]  : understands "set volume to 80%", "mute", "torch on", "call Rahul"
 *                       (English / Marathi / Hindi) WITHOUT asking the AI, so it is instant and offline.
 *  - [execute]        : runs a volume / torch / call action returned by Gemini, Groq or the PC backend.
 */
class PhoneHardwareExecutor(context: Context) {

    /** [fallThrough] = "I couldn't do it, let the AI try" (e.g. contact name not found). */
    data class Outcome(val spoken: String, val success: Boolean, val fallThrough: Boolean = false)

    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val camera = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var volumeBeforeMute: Int? = null
    private val torchStates = ConcurrentHashMap<String, Boolean>()

    init {
        // Keeps the real flashlight state, so "torch" (toggle) and "torch off" are always right.
        camera.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                torchStates[cameraId] = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    // ================================================================================================
    // Public API
    // ================================================================================================

    fun handles(actionName: String): Boolean = actionName.lowercase() in HARDWARE_ACTIONS

    /** Parses and immediately executes a spoken/typed command. Returns null if it is not a hardware command. */
    fun tryHandleText(text: String): Outcome? {
        val action = interpret(text) ?: return null
        return execute(action)
    }

    fun execute(a: PhoneAction): Outcome? = when (a.action.lowercase()) {
        "volume", "set_volume", "mute", "unmute" -> volume(a)
        "torch", "flashlight", "flash" -> torch(a)
        "call", "dial" -> call(a)
        "open_app", "app", "open" -> openApp(a.str("app", "name", "package"))
        "close_app", "close", "stop_app", "exit_app" -> closeApp(a.str("app", "name", "package"))
        else -> null
    }

    // ================================================================================================
    // Intent parsing (rule based, no AI)
    // ================================================================================================

    fun interpret(raw: String): PhoneAction? {
        val t = normalize(raw)
        return parseTorch(t) ?: parseVolume(t) ?: parseCall(raw.trim()) ?: parseCloseApp(raw.trim()) ?: parseAppLaunch(raw.trim())
    }

    /** Lower-cases, converts Devanagari digits (८०) to ASCII (80), and transliterates common Devanagari voice keywords. */
    private fun normalize(s: String): String {
        val asciiDigits = buildString {
            for (c in s.lowercase().trim()) append(if (c in '\u0966'..'\u096F') '0' + (c - '\u0966') else c)
        }
        var result = asciiDigits
        val transliterations = mapOf(
            "ओपन" to "open", "उघडा" to "open", "उघड" to "open", "खोलो" to "open",
            "व्हाट्सएप" to "whatsapp", "व्हॉट्सॲप" to "whatsapp", "व्हाट्सॲप" to "whatsapp",
            "यूट्यूब" to "youtube", "युट्यूब" to "youtube",
            "क्रोम" to "chrome",
            "वॉल्यूम" to "volume", "व्हॉल्यूम" to "volume", "आवाज" to "volume",
            "टॉर्च" to "torch", "टोर्च" to "torch", "फ्लॅशलाइट" to "torch", "फ्लॅशलाईट" to "torch",
            "कॉल" to "call", "फोन" to "call",
            "बंद" to "close", "बन्द" to "close"
        )
        for ((devanagari, english) in transliterations) {
            if (result.contains(devanagari)) {
                result = result.replace(devanagari, english)
            }
        }
        return result
    }

    private fun act(name: String, vararg kv: Pair<String, Any>) = PhoneAction(
        name,
        buildJsonObject {
            for ((k, v) in kv) if (v is Int) put(k, v) else put(k, v.toString())
        }
    )

    private fun parseTorch(t: String): PhoneAction? {
        if (!TORCH_WORDS.containsMatchIn(t)) return null
        val state = when {
            TORCH_OFF.containsMatchIn(t) -> "off"
            TORCH_ON.containsMatchIn(t) -> "on"
            else -> "toggle"
        }
        return act("torch", "state" to state)
    }

    private fun parseVolume(t: String): PhoneAction? {
        if (!VOLUME_WORDS.containsMatchIn(t)) return null
        if (UNMUTE.containsMatchIn(t)) return act("volume", "direction" to "unmute")
        if (MUTE.containsMatchIn(t)) return act("volume", "direction" to "mute")

        val num = Regex("""\d{1,3}""").find(t)?.value?.toIntOrNull()
        val up = UP.containsMatchIn(t)
        val down = DOWN.containsMatchIn(t)
        val absolute = TO_NUMBER.containsMatchIn(t) // "to 40", "at 40"

        return when {
            MAX.containsMatchIn(t) -> act("volume", "level" to 100)
            HALF.containsMatchIn(t) -> act("volume", "level" to 50)
            num != null && (up || down) && !absolute -> act("volume", "delta" to if (up) num else -num)
            num != null -> act("volume", "level" to num.coerceIn(0, 100))
            up -> act("volume", "direction" to "up")
            down -> act("volume", "direction" to "down")
            else -> null
        }
    }

    private fun parseCall(raw: String): PhoneAction? {
        val target = CALL_EN.find(raw)?.groupValues?.get(1)?.trim()
            ?: CALL_MR.find(raw)?.groupValues?.get(1)?.trim()
            ?: return null
        if (target.isEmpty()) return null
        val digits = target.filter { it.isDigit() || it == '+' }
        return if (digits.count { it.isDigit() } >= 7) act("call", "number" to digits)
        else act("call", "name" to target)
    }

    // ================================================================================================
    // VOLUME  (AudioManager.setStreamVolume)
    // ================================================================================================

    private fun volume(a: PhoneAction): Outcome {
        return try {
            val name = a.action.lowercase()
            val direction = a.str("direction", "state", "command")?.lowercase()
                ?: if (name == "mute" || name == "unmute") name else null
            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val level = a.percent("level", "percent", "value", "volume", "amount", "target")
            val delta = a.percent("delta", "by", "step", "change")

            when (direction) {
                "mute" -> {
                    if (current > 0) volumeBeforeMute = current
                    applyVolume(0, maxVol, "Volume muted.")
                }
                "unmute" -> {
                    val restore = volumeBeforeMute ?: max(1, maxVol / 2)
                    volumeBeforeMute = null
                    applyVolume(restore, maxVol)
                }
                "up", "increase", "raise", "louder", "higher" -> {
                    val steps = (delta ?: level)?.let { percentToSteps(it, maxVol) } ?: defaultStep(maxVol)
                    applyVolume(current + steps, maxVol)
                }
                "down", "decrease", "lower", "quieter", "reduce", "softer" -> {
                    val steps = (delta ?: level)?.let { percentToSteps(it, maxVol) } ?: defaultStep(maxVol)
                    applyVolume(current - steps, maxVol)
                }
                else -> when {
                    level != null -> applyVolume(percentToLevel(level, maxVol), maxVol)
                    delta != null -> {
                        val steps = percentToSteps(kotlin.math.abs(delta), maxVol)
                        applyVolume(current + if (delta >= 0) steps else -steps, maxVol)
                    }
                    else -> Outcome("What volume level should I set, sir?", false)
                }
            }
        } catch (_: SecurityException) {
            Outcome("The phone didn't allow me to change the volume.", false)
        } catch (e: Exception) {
            Outcome("I couldn't change the volume: ${e.message}", false)
        }
    }

    /** Physically sets the music volume and verifies that Android really applied it. */
    private fun applyVolume(target: Int, maxVol: Int, message: String? = null): Outcome {
        val t = target.coerceIn(0, maxVol)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, t, AudioManager.FLAG_SHOW_UI)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = if (maxVol > 0) (now * 100f / maxVol).roundToInt() else 0
        return if (now == t) Outcome(message ?: "Volume set to $pct percent.", true)
        else Outcome("I tried, but the phone is holding the volume at $pct percent.", false)
    }

    private fun percentToLevel(p: Int, maxVol: Int) = (p.coerceIn(0, 100) / 100f * maxVol).roundToInt()
    private fun percentToSteps(p: Int, maxVol: Int) = max(1, (p.coerceIn(0, 100) / 100f * maxVol).roundToInt())
    private fun defaultStep(maxVol: Int) = max(1, (maxVol * 0.1f).roundToInt())

    /** Accepts 80, "80", "80%", "80.0", "-20". */
    private fun PhoneAction.percent(vararg keys: String): Int? {
        for (k in keys) {
            val s = str(k) ?: continue
            Regex("""-?\d+(?:\.\d+)?""").find(s)?.value?.toDoubleOrNull()?.let { return it.roundToInt() }
        }
        return null
    }

    // ================================================================================================
    // FLASHLIGHT  (CameraManager.setTorchMode)
    // ================================================================================================

    private fun torch(a: PhoneAction): Outcome {
        val id = flashCameraId() ?: return Outcome("This phone has no flashlight, sir.", false)
        val want = when (a.str("state", "mode", "value", "command")?.lowercase()) {
            "on", "true", "enable", "1" -> true
            "off", "false", "disable", "0" -> false
            else -> !(torchStates[id] ?: false)
        }
        return try {
            camera.setTorchMode(id, want)
            torchStates[id] = want
            Outcome(if (want) "Flashlight on." else "Flashlight off.", true)
        } catch (_: CameraAccessException) {
            Outcome("I couldn't reach the flashlight. Another app may be using the camera.", false)
        } catch (_: IllegalArgumentException) {
            Outcome("This phone's flashlight can't be controlled.", false)
        }
    }

    private fun flashCameraId(): String? {
        val withFlash = camera.cameraIdList.filter {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        return withFlash.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    }

    // ================================================================================================
    // CALL  (Intent.ACTION_CALL)
    // ================================================================================================

    private fun call(a: PhoneAction): Outcome {
        val typed = a.str("number", "phone", "phone_number")?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val name = a.str("name", "contact", "to")

        val display: String
        val number: String
        if (!typed.isNullOrEmpty()) {
            number = typed
            display = typed
        } else if (!name.isNullOrBlank()) {
            if (!has(Manifest.permission.READ_CONTACTS)) {
                return Outcome("Please allow the Contacts permission so I can find $name.", false)
            }
            val found = lookupContact(name)
                ?: return Outcome("I couldn't find $name in your contacts, sir.", false, fallThrough = true)
            display = found.first
            number = found.second
        } else {
            return Outcome("Whom should I call, sir?", false)
        }

        val canCall = has(Manifest.permission.CALL_PHONE)

        // TelecomManager.placeCall works from the background (a plain startActivity does not).
        if (canCall) {
            try {
                val telecom = app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
                return Outcome("Calling $display.", true)
            } catch (_: SecurityException) {
                // fall back to the intent below
            } catch (_: Exception) {
                // fall back to the intent below
            }
        }
        val intent = Intent(
            if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.fromParts("tel", number, null) // same as Uri.parse("tel:$number"), but safe for + * #
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            BackgroundLauncher.start(app, intent, display)
            if (canCall) Outcome("Calling $display.", true)
            else Outcome("Opened the dialer for $display. Allow the Phone permission so I can call directly.", false)
        } catch (_: SecurityException) {
            Outcome("The phone blocked the call. Please allow the Phone permission.", false)
        } catch (e: Exception) {
            Outcome("I couldn't place the call: ${e.message}", false)
        }
    }

    /** Best match wins: exact name, then "starts with", then "contains". */
    private fun lookupContact(query: String): Pair<String, String>? {
        app.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )?.use { c ->
            var best: Pair<String, String>? = null
            var bestScore = Int.MAX_VALUE
            while (c.moveToNext()) {
                val display = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val rank = when {
                    display.equals(query, true) -> 0
                    display.startsWith(query, true) -> 1
                    else -> 2
                }
                val score = rank * 1000 + display.length
                if (score < bestScore) {
                    best = display to number
                    bestScore = score
                }
            }
            return best
        }
        return null
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    // ================================================================================================
    // APP LAUNCHING & CLOSING
    // ================================================================================================

    private fun parseCloseApp(raw: String): PhoneAction? {
        val t = raw.lowercase().trim()
        if (!CLOSE_WORDS.containsMatchIn(t)) return null
        val appName = when {
            t.contains("whatsapp") -> "whatsapp"
            t.contains("youtube") -> "youtube"
            t.contains("chrome") -> "chrome"
            t.contains("spotify") -> "spotify"
            t.startsWith("close ") -> t.removePrefix("close ").trim()
            t.startsWith("stop ") -> t.removePrefix("stop ").trim()
            t.startsWith("exit ") -> t.removePrefix("exit ").trim()
            t.endsWith(" band kara") -> t.removeSuffix(" band kara").trim()
            t.endsWith(" band karo") -> t.removeSuffix(" band karo").trim()
            t.endsWith(" बंद करा") -> t.removeSuffix(" बंद करा").trim()
            t.endsWith(" बंद करो") -> t.removeSuffix(" बंद करो").trim()
            else -> "app"
        }
        return act("close_app", "app" to appName)
    }

    private fun parseAppLaunch(raw: String): PhoneAction? {
        val t = raw.lowercase().trim()
        if (CLOSE_WORDS.containsMatchIn(t)) return null
        val appName = when {
            t.contains("youtube") -> "youtube"
            t.contains("spotify") || t == "play music" -> "spotify"
            t.contains("chrome") || t == "open browser" -> "chrome"
            t.contains("whatsapp") -> "whatsapp"
            t.startsWith("open ") -> t.removePrefix("open ").trim()
            t.startsWith("launch ") -> t.removePrefix("launch ").trim()
            else -> null
        } ?: return null
        return act("open_app", "app" to appName)
    }

    private fun closeApp(name: String?): Outcome {
        val service = JarvisAccessibilityService.instance
        val appLabel = if (name.isNullOrBlank() || name == "app") "the app" else name
        return if (service != null) {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            Outcome("Closed $appLabel.", true)
        } else {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(homeIntent)
                Outcome("Closed $appLabel.", true)
            } catch (e: Exception) {
                Outcome("Could not close $appLabel: ${e.message}", false)
            }
        }
    }

    private fun openApp(name: String?): Outcome {
        if (name.isNullOrBlank()) return Outcome("Which app should I open?", false)
        val pm = app.packageManager
        val launch = findLaunchIntent(pm, name)
        return if (launch != null) {
            try {
                BackgroundLauncher.start(app, launch, name)
                Outcome("Opening $name.", true)
            } catch (e: Exception) {
                Outcome("Could not launch $name: ${e.message}", false)
            }
        } else {
            Outcome("$name is not installed on your phone, sir. Please install it from the Play Store.", false, fallThrough = false)
        }
    }

    private fun findLaunchIntent(pm: PackageManager, rawName: String): Intent? {
        val queryName = rawName.lowercase().trim()
        if (queryName.contains('.')) {
            pm.getLaunchIntentForPackage(queryName)?.let { return it }
        }

        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(query, 0)
        }
        if (apps.isEmpty()) return null

        val searchTerms = when {
            queryName == "notepad" || queryName == "notes" || queryName == "note" -> listOf("notepad", "notes", "note", "keep", "memo")
            queryName == "freefire" || queryName == "free fire" || queryName == "ff" -> listOf("free fire", "freefire", "garena", "ff")
            queryName == "gallery" || queryName == "photos" -> listOf("photos", "gallery")
            queryName == "files" || queryName == "file manager" -> listOf("files", "file manager", "my files")
            queryName == "camera" -> listOf("camera")
            queryName == "calculator" -> listOf("calc", "calculator")
            else -> listOf(queryName)
        }

        for (term in searchTerms) {
            val termClean = term.replace(" ", "")
            val match = apps.firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase()
                label == term || label.contains(term) || label.replace(" ", "").contains(termClean)
            }
            if (match != null) {
                return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            }
        }

        for (term in searchTerms) {
            val termClean = term.replace(" ", "")
            val match = apps.firstOrNull {
                val pkg = it.activityInfo.packageName.lowercase()
                pkg.contains(termClean)
            }
            if (match != null) {
                return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            }
        }

        return null
    }

    // ================================================================================================

    private companion object {
        val HARDWARE_ACTIONS = setOf(
            "volume", "set_volume", "mute", "unmute", "torch", "flashlight", "flash", "call", "dial",
            "open_app", "app", "open", "close_app", "close", "stop_app", "exit_app"
        )
        val CLOSE_WORDS = Regex("""\b(close|stop|exit|quit|band)\b|बंद|बन्द""")

        val VOLUME_WORDS = Regex("""\b(volume|sound|audio|louder|quieter|softer|mute|unmute|silent)\b|आवाज|वॉल्यूम|व्हॉल्यूम|म्यूट|अनम्यूट|ध्वनी|ध्वनि""")
        val UNMUTE = Regex("""\bunmute\b|अनम्यूट|आवाज\s*चालू""")
        val MUTE = Regex("""\bmute\b|\bsilent\b|म्यूट|आवाज\s*बंद|\b(volume|sound)\s*off\b""")
        val UP = Regex("""\b(increase|raise|up|louder|higher|boost|more)\b|वाढव|वाढवा|जास्त""")
        val DOWN = Regex("""\b(decrease|lower|down|reduce|quieter|softer|less)\b|कमी""")
        val MAX = Regex("""\b(max|maximum|full)\b|फुल|जास्तीत\s*जास्त""")
        val HALF = Regex("""\bhalf\b|अर्धा|अर्धी""")
        val TO_NUMBER = Regex("""\b(to|at)\s*\d""")

        val TORCH_WORDS = Regex("""\b(flashlight|flash light|torch)\b|टॉर्च|टोर्च|फ्लॅशलाइट|फ्लॅशलाईट|फ्लैशलाइट""")
        val TORCH_OFF = Regex("""\b(off|disable|close|stop|band)\b|बंद|बन्द""")
        val TORCH_ON = Regex("""\b(on|enable|start|open|chalu|suru|laav|laava)\b|चालू|चालु|सुरू|लाव|लावा""")

        val CALL_EN = Regex(
            """^\s*(?:(?:hey\s+)?jarvis[,\s]+)?(?:please\s+)?(?:call|dial|phone|ring)\s+(?:to\s+)?(.+?)\s*(?:please|now)?\s*[.!]?\s*$""",
            RegexOption.IGNORE_CASE
        )
        val CALL_MR = Regex(
            """^\s*(.+?)\s*(?:ला|ल|ko)?\s+(?:कॉल|फोन|call)\s*(?:कर|करा|लगा|लगाओ|कर\s*दे|कर\s*दो)\s*[.!]?\s*$""",
            RegexOption.IGNORE_CASE
        )
    }
}
