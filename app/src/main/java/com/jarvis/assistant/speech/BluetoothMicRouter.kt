package com.jarvis.assistant.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** User choice: listen through the Bluetooth headset mic instead of the phone mic. Default OFF. */
object MicPrefs {
    private const val FILE = "jarvis_mic"
    private const val KEY = "bt_mic"
    fun useBluetoothMic(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setUseBluetoothMic(c: Context, v: Boolean) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply()
}

/**
 * Routes the microphone to a connected Bluetooth headset (HFP/SCO or BLE headset).
 * start() blocks up to ~3 s: call it from a worker thread. Always pair it with stop().
 *
 * Trade-off (Android limitation): while the Bluetooth mic is open the headset switches to
 * "call" profile, so music/audio quality drops to phone-call quality and the headset battery drains faster.
 */
class BluetoothMicRouter(context: Context) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var prevMode = AudioManager.MODE_NORMAL
    private var started = false

    fun start(): Boolean {
        if (started) return true
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false

        prevMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        started = true

        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull { isBluetooth(it) }
            if (dev == null || !am.setCommunicationDevice(dev)) { stop(); return false }
        } else {
            @Suppress("DEPRECATION") am.startBluetoothSco()
            @Suppress("DEPRECATION") am.isBluetoothScoOn = true
        }

        val end = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < end) {
            if (findInput(ctx) != null) return true
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        }
        stop()
        return false
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION") am.stopBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = false
            }
        } catch (_: Exception) {}
        am.mode = prevMode
    }

    companion object {
        fun isBluetooth(d: AudioDeviceInfo): Boolean =
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

        fun findInput(context: Context): AudioDeviceInfo? =
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isBluetooth(it) }
    }
}
