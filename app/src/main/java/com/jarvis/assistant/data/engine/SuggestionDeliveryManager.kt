package com.jarvis.assistant.data.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.core.AppState
import com.jarvis.assistant.data.model.ContextSuggestion
import com.jarvis.assistant.data.prefs.JarvisSettings
import com.jarvis.assistant.speech.VoiceInteractionManager
import com.jarvis.assistant.ui.MainActivity

class SuggestionDeliveryManager(
    private val context: Context,
    private val voiceManager: VoiceInteractionManager
) {
    fun deliverSuggestion(suggestion: ContextSuggestion, settings: JarvisSettings) {
        if (!suggestion.isValid) return

        // 1. If App is in foreground, state flow in MainActivity / UiState automatically displays the In-App HUD Card
        if (AppState.inForeground) {
            if (settings.voiceSuggestionsEnabled && voiceManager.mode != VoiceInteractionManager.Mode.COMMAND) {
                voiceManager.speak("Sir, I have a suggestion: ${suggestion.title}")
            }
            return
        }

        // 2. If app is in background, check delivery mode settings
        when (settings.suggestionDeliveryMode.uppercase()) {
            "VOICE" -> {
                if (settings.voiceSuggestionsEnabled && voiceManager.mode != VoiceInteractionManager.Mode.COMMAND) {
                    voiceManager.speak("Sir, ${suggestion.title}. ${suggestion.detail}")
                } else {
                    postNotification(suggestion)
                }
            }
            "NOTIFICATION" -> {
                postNotification(suggestion)
            }
            else -> { // "IN_APP" or "FLOATING"
                postNotification(suggestion)
            }
        }
    }

    private fun postNotification(suggestion: ContextSuggestion) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "J.A.R.V.I.S. Suggestions", NotificationManager.IMPORTANCE_HIGH)
        )

        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SUGGESTION_COMMAND", suggestion.command)
        }

        val pending = PendingIntent.getActivity(
            app,
            suggestion.command.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reactor_icon)
            .setContentTitle("J.A.R.V.I.S. Suggestion: ${suggestion.title}")
            .setContentText(suggestion.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(suggestion.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(R.drawable.ic_reactor_icon, "Let's Do It", pending)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    private companion object {
        const val CHANNEL_ID = "jarvis_suggestions"
        const val NOTIFICATION_ID = 3001
    }
}
