package com.jarvis.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.service.JarvisBackgroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, JarvisBackgroundService::class.java))
        } catch (e: Exception) {
            // Android 15 blocks microphone foreground services from BOOT_COMPLETED.
            // Opening the app once after boot starts the service.
            Log.w("BootReceiver", "Could not auto-start on boot: ${e.message}")
        }
    }
}
