package com.jarvis.assistant.automation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.data.model.PhoneAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Runs phone-side actions requested by the AI (phone mode) or by the PC backend (PC mode). */
class PhoneActionExecutor(private val context: Context) {

    private val hardware = PhoneHardwareExecutor(context)

    /** Returns a short human-readable outcome; never throws. */
    suspend fun execute(a: PhoneAction): String = withContext(Dispatchers.Main) {
        try {
            when (a.action.lowercase()) {
                // apps & communication
                "open_app" -> openApp(a.str("app", "name", "package"))
                // volume / flashlight / calls are really executed by PhoneHardwareExecutor
                "call", "dial", "volume", "set_volume", "mute", "unmute", "torch", "flashlight" ->
                    hardware.execute(a)?.spoken ?: "That didn't work."
                "sms" -> sms(a)
                "whatsapp" -> whatsapp(a)
                "email" -> email(a)
                // utilities
                "alarm" -> alarm(a)
                "timer" -> timer(a)
                "web_search" -> webSearch(a.str("query", "text"))
                "open_url" -> openUrl(a.str("url", "link"))
                "youtube" -> youtube(a.str("query", "text"))
                "navigate" -> navigate(a.str("destination", "place", "query"))
                "camera" -> camera()
                "settings" -> openSettings(a.str("page", "name"))
                // hardware
                "brightness" -> brightness(a)
                "media" -> media(a.str("command", "action"))
                // screen control (Accessibility)
                "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Went back.")
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Went home.")
                "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Opened recent apps.")
                "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opened notifications.")
                "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Opened quick settings.")
                "screenshot" -> if (Build.VERSION.SDK_INT >= 28)
                    global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken.")
                else "Screenshots need Android 9 or newer."
                "lock_screen" -> if (Build.VERSION.SDK_INT >= 28)
                    global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Screen locked.")
                else "Locking needs Android 9 or newer."
                "scroll" -> scroll(a.str("direction"))
                "click" -> click(a.str("text", "label"))
                "click_index" -> clickIndex(a.str("index")?.toIntOrNull())
                "type" -> type(a.str("index")?.toIntOrNull(), a.str("text"))
                "wait" -> { delay(1500); "Waited." }
                else -> "Unsupported action: ${a.action}"
            }
        } catch (e: Exception) {
            "Action failed: ${e.message}"
        }
    }

    // ---- apps -------------------------------------------------------------------------------

    private fun openApp(name: String?): String {
        if (name.isNullOrBlank()) return "Which app should I open?"
        val pm = context.packageManager
        val launch: Intent? = if (name.contains('.')) {
            pm.getLaunchIntentForPackage(name)
        } else {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.queryIntentActivities(query, 0)
            }
            val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
                ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        }
        return if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            "Opening $name."
        } else {
            "I couldn't find an app called $name."
        }
    }

    private fun launch(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens [uri] in [pkg] if installed, otherwise in whatever can handle it. */
    private fun view(uri: Uri, pkg: String? = null) {
        val base = Intent(Intent.ACTION_VIEW, uri)
        if (pkg != null) {
            try { launch(Intent(base).setPackage(pkg)); return } catch (e: Exception) { /* fall through */ }
        }
        launch(base)
    }

    // ---- calls & messages ---------------------------------------------------------------------

    private suspend fun sms(a: PhoneAction): String {
        val message = a.str("message", "text").orEmpty()
        val name = a.str("name", "contact").orEmpty()
        val number = a.str("number", "phone") ?: lookupNumber(name)
            ?: return "I couldn't find a number for ${name.ifBlank { "that contact" }}."
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).putExtra("sms_body", message))
        val service = JarvisAccessibilityService.instance
            ?: return "Opened the message. Enable Accessibility so I can press send."
        if (message.isBlank()) return "Opened the message."
        return if (service.pressSend()) "Message sent." else "I opened the message but couldn't press send."
    }

    private suspend fun whatsapp(a: PhoneAction): String {
        val message = a.str("message", "text").orEmpty()
        val name = a.str("name", "contact").orEmpty()
        val raw = a.str("number", "phone") ?: lookupNumber(name)
            ?: return "I couldn't find a number for ${name.ifBlank { "that contact" }}."
        var digits = raw.filter { it.isDigit() }
        if (digits.length == 10) digits = DEFAULT_COUNTRY_CODE + digits

        view(Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}"), "com.whatsapp")

        val service = JarvisAccessibilityService.instance
            ?: return "Opened WhatsApp. Enable the Accessibility service so I can press send."
        if (message.isBlank()) return "Opened the chat."
        return if (service.automateWhatsAppSend(name, message)) "Message sent." else "I opened the chat but couldn't press send."
    }

    private fun email(a: PhoneAction): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(a.str("to", "email").orEmpty()))
            .putExtra(Intent.EXTRA_SUBJECT, a.str("subject").orEmpty())
            .putExtra(Intent.EXTRA_TEXT, a.str("body", "message", "text").orEmpty())
        launch(intent)
        return "Opened your email app."
    }

    private fun lookupNumber(name: String): String? {
        if (name.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    // ---- utilities ----------------------------------------------------------------------------

    private fun alarm(a: PhoneAction): String {
        val hour = a.str("hour")?.toIntOrNull() ?: return "What time should I set the alarm for?"
        val minutes = a.str("minutes", "minute")?.toIntOrNull() ?: 0
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                .putExtra(AlarmClock.EXTRA_MINUTES, minutes.coerceIn(0, 59))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.str("message", "label").orEmpty())
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Alarm set for %02d:%02d.".format(hour.coerceIn(0, 23), minutes.coerceIn(0, 59))
    }

    private fun timer(a: PhoneAction): String {
        val seconds = a.str("seconds")?.toIntOrNull()
            ?: a.str("minutes")?.toDoubleOrNull()?.let { (it * 60).toInt() }
            ?: return "For how long should I set the timer?"
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Timer started."
    }

    private fun webSearch(query: String?): String {
        if (query.isNullOrBlank()) return "What should I search for?"
        try {
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        } catch (e: Exception) {
            view(Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
        }
        return "Searching for $query."
    }

    private fun openUrl(url: String?): String {
        if (url.isNullOrBlank()) return "Which link should I open?"
        val full = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        view(Uri.parse(full))
        return "Opening $url."
    }

    private fun youtube(query: String?): String {
        if (query.isNullOrBlank()) return "What should I search on YouTube?"
        view(Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"), "com.google.android.youtube")
        return "Searching YouTube for $query."
    }

    private fun navigate(destination: String?): String {
        if (destination.isNullOrBlank()) return "Where should I navigate to?"
        try {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
                .setPackage("com.google.android.apps.maps"))
        } catch (e: Exception) {
            view(Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
        }
        return "Navigating to $destination."
    }

    private fun camera(): String {
        launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        return "Opening the camera."
    }

    private fun openSettings(page: String?): String {
        val action = when (page?.lowercase()) {
            "wifi", "wi-fi", "internet" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "date", "time" -> Settings.ACTION_DATE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        return "Opened ${page ?: "settings"}."
    }

    // ---- hardware -----------------------------------------------------------------------------

    private fun brightness(a: PhoneAction): String {
        if (!Settings.System.canWrite(context)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return "Please allow 'Modify system settings' for me, then ask again."
        }
        val level = (a.str("level", "percent")?.toIntOrNull() ?: return "What brightness level?").coerceIn(0, 100)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level * 255 / 100)
        return "Brightness set to $level percent."
    }

    /** Controls whichever music/video app is playing (Spotify, YouTube Music, etc.). */
    private fun media(command: String?): String {
        val code = when (command?.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle", "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "I didn't understand that media command."
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Done."
    }

    // ---- accessibility-driven ------------------------------------------------------------------

    private fun global(action: Int, ok: String): String {
        val s = JarvisAccessibilityService.instance ?: return NEEDS_ACCESSIBILITY
        return if (s.performGlobalAction(action)) ok else "That didn't work."
    }

    private fun scroll(direction: String?): String {
        val s = JarvisAccessibilityService.instance ?: return NEEDS_ACCESSIBILITY
        val ok = when (direction?.lowercase()) {
            "up", "back", "backward" -> s.scrollBackward()
            else -> s.scrollForward()
        }
        return if (ok) "Scrolled." else "Nothing to scroll."
    }

    private fun click(text: String?): String {
        if (text.isNullOrBlank()) return "What should I tap?"
        val s = JarvisAccessibilityService.instance ?: return NEEDS_ACCESSIBILITY
        return if (s.clickElementByText(text)) "Tapped $text." else "I couldn't find $text on screen."
    }

    private fun clickIndex(index: Int?): String {
        if (index == null) return "Which item should I tap?"
        val s = JarvisAccessibilityService.instance ?: return NEEDS_ACCESSIBILITY
        return if (s.clickIndex(index)) "Tapped item $index." else "Couldn't tap item $index."
    }

    private fun type(index: Int?, text: String?): String {
        if (text.isNullOrBlank()) return "What should I type?"
        val s = JarvisAccessibilityService.instance ?: return NEEDS_ACCESSIBILITY
        val ok = if (index != null) s.typeIndex(index, text) else s.typeIntoFocused(text)
        return if (ok) "Typed." else "I couldn't find a text field."
    }

    private companion object {
        const val NEEDS_ACCESSIBILITY = "Please enable the Accessibility service in settings first."
        /** Prefixed to 10-digit numbers for WhatsApp links. Change if you are outside India. */
        const val DEFAULT_COUNTRY_CODE = "91"
    }
}
