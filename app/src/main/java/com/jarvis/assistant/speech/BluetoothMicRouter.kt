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
            if (dev != null && am.setCommunicationDevice(dev)) {
                return true
            }
        }

        try {
            startSco(am)
        } catch (_: Exception) {}

        val end = SystemClock.elapsedRealtime() + 2000
        while (SystemClock.elapsedRealtime() < end) {
            if (findInput(ctx) != null) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }

        if (Build.VERSION.SDK_INT >= 31 && am.communicationDevice != null) {
            return true
        }
        if (isScoOn(am)) {
            return true
        }

        stop()
        return false
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            }
            stopSco(am)
        } catch (_: Exception) {}
        try {
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun startSco(audioManager: AudioManager) {
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    @Suppress("DEPRECATION")
    private fun stopSco(audioManager: AudioManager) {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
    }

    @Suppress("DEPRECATION")
    private fun isScoOn(audioManager: AudioManager): Boolean = audioManager.isBluetoothScoOn

    companion object {
        /** Bluetooth devices that can capture audio (SCO / BLE headset). A2DP and BLE speakers are output only. */
        fun isBluetooth(d: AudioDeviceInfo): Boolean =
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

        fun findInput(context: Context): AudioDeviceInfo? {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val inputDev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isBluetooth(it) }
            if (inputDev != null) return inputDev
            if (Build.VERSION.SDK_INT >= 31) {
                return am.availableCommunicationDevices.firstOrNull { isBluetooth(it) }
            }
            return null
        }
    }
}
