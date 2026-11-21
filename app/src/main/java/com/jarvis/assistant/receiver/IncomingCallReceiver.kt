package com.jarvis.assistant.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hands-Free Incoming Call Announcer & Voice Accept/Reject receiver.
 * Speaks the caller's name and listens for "answer" / "decline" commands.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val callerName = lookupCallerName(context, rawNumber) ?: "Unknown Caller"

        val app = context.applicationContext as? JarvisApp ?: return
        val voice = app.core.voice

        scope.launch {
            delay(500) // Short pause to avoid overlapping with initial ringtone
            voice.speakThenCommand("Incoming call from $callerName. Say answer or decline, sir.")

            val prevTranscript = voice.onTranscript
            voice.onTranscript = { transcript ->
                voice.onTranscript = prevTranscript
                val t = transcript.lowercase().trim()
                when {
                    t.contains("answer") || t.contains("accept") || t.contains("pick") ||
                        t.contains("उचल") || t.contains("उचला") || t.contains("हा") -> {
                        answerCall(context)
                    }
                    t.contains("decline") || t.contains("reject") || t.contains("cut") ||
                        t.contains("cancel") || t.contains("बंद") || t.contains("नाही") -> {
                        declineCall(context)
                    }
                    else -> {
                        prevTranscript?.invoke(transcript)
                    }
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun answerCall(context: Context) {
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telecom.acceptRingingCall()
                }
            }
        } catch (_: Exception) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun declineCall(context: Context) {
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecom.endCall()
                }
            }
        } catch (_: Exception) {}
    }

    private fun lookupCallerName(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return number
        }
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (_: Exception) {}
        return number
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
