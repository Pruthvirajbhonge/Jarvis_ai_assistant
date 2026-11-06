package com.jarvis.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.core.JarvisCore
import com.jarvis.assistant.overlay.FloatingOrbManager
import com.jarvis.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 24/7 foreground service: keeps the wake loop, PC link and floating orb alive. */
class JarvisBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var orb: FloatingOrbManager? = null
    private val core: JarvisCore get() = (application as JarvisApp).core

    override fun onCreate() {
        super.onCreate()
        try {
            startAsForeground()
        } catch (e: Exception) {
            // e.g. microphone permission not granted yet (Android 14+) or started from the background
            Log.w(TAG, "Could not enter foreground: ${e.message}")
            stopSelf()
            return
        }
        acquireWakeLock()
        orb = FloatingOrbManager(this) { core.voice.startCommandSession() }
        core.startBackground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called again every time the app opens or settings are saved: re-evaluate the orb.
        scope.launch {
            val enabled = core.settings.current().floatingOrb
            if (enabled) orb?.show() else orb?.hide()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "J.A.R.V.I.S. Background Listener", NotificationManager.IMPORTANCE_LOW)
        )
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. is active")
            .setContentText("Listening for “Hey Jarvis” in the background…")
            .setSmallIcon(R.drawable.ic_reactor_icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
        orb?.hide()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        core.stopBackground()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "JarvisService"
        const val CHANNEL_ID = "jarvis_service_channel"
        const val NOTIFICATION_ID = 101
    }
}
