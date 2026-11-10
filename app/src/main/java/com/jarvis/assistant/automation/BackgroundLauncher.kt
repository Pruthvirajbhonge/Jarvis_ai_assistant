package com.jarvis.assistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.core.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts activities (open app, SMS, maps, YouTube...) when Jarvis is running in the BACKGROUND.
 *
 * Android 10+ silently ignores startActivity() from a background service, so the reply was spoken
 * but nothing opened. This launcher:
 *   1. launches directly when Android allows it (Jarvis visible, or "Display over other apps" is on
 *      and the floating orb is showing),
 *   2. checks (via the Accessibility service) that the screen really changed,
 *   3. if the launch was blocked, shows a tap-to-open notification and tells the user honestly.
 */
object BackgroundLauncher {

    /** Set by JarvisCore: called on the main thread when Android blocked a launch. */
    @Volatile var onBlocked: ((label: String) -> Unit)? = null

    private const val CHANNEL_ID = "jarvis_actions"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start(context: Context, intent: Intent, label: String? = null) {
        val app = context.applicationContext
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val name = label ?: appLabel(app, intent) ?: "the app"

        val service = JarvisAccessibilityService.instance
        val canLaunchDirectly = AppState.inForeground ||
            Settings.canDrawOverlays(app) ||
            service != null

        // Nothing allows a background launch and we can't verify it: don't pretend, ask for a tap.
        if (!canLaunchDirectly) {
            blocked(app, intent, name)
            return
        }

        val options = if (Build.VERSION.SDK_INT >= 34) {
            try {
                android.app.ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        val before = service?.foregroundPackage
        try {
            if (options != null) {
                app.startActivity(intent, options)
            } else {
                app.startActivity(intent)
            }
        } catch (e: ActivityNotFoundException) {
            throw e // let callers fall back (e.g. YouTube not installed -> open in the browser)
        } catch (e: Exception) {
            blocked(app, intent, name)
            return
        }

        // Alarm/timer with SKIP_UI never show a screen, so there is nothing to verify.
        val silent = intent.action == AlarmClock.ACTION_SET_ALARM ||
            intent.action == AlarmClock.ACTION_SET_TIMER ||
            intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)

        if (service != null && !AppState.inForeground && !silent) {
            val target = intent.resolveActivity(app.packageManager)?.packageName?.takeIf { it != "android" }
                ?: intent.`package`
            scope.launch {
                delay(1600L) // give the target app time to come to the front
                val now = service.foregroundPackage
                val arrived = (now != null && now != before) || (target != null && now == target)
                if (!arrived) blocked(app, intent, name)
            }
        }
    }

    private fun blocked(app: Context, intent: Intent, label: String) {
        postTapNotification(app, intent, label)
        onBlocked?.invoke(label)
    }

    private fun postTapNotification(app: Context, intent: Intent, label: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis actions", NotificationManager.IMPORTANCE_HIGH)
        )
        val pending = PendingIntent.getActivity(
            app, label.hashCode(), Intent(intent), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "Android blocked opening it from the background. " +
            "Allow “Display over other apps” in Jarvis to skip this tap."
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reactor_icon)
            .setContentTitle("Tap to open $label")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            nm.notify(2000 + (label.hashCode() and 0xFF), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the spoken message is all we can do.
        }
    }

    private fun appLabel(app: Context, intent: Intent): String? = try {
        val pm = app.packageManager
        intent.resolveActivity(pm)?.packageName?.let { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
    } catch (e: Exception) {
        null
    }
}
