package com.jarvis.assistant.speech

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Which physical microphone JARVIS listens to. Enrollment, verification and wake all use the SAME route. */
enum class MicRoute { PHONE, WIRED, BLUETOOTH }

object MicRouting {
    private const val FILE = "jarvis_mic"
    private const val KEY = "route"
    private const val KEY_AUTO_BT = "auto_bt"

    /** Set by the voice manager: false = Bluetooth must not be used now (voice auth is ON but no Bluetooth voiceprint). */
    @Volatile var btAllowed: (() -> Boolean)? = null
    @Volatile private var btBlockedUntil = 0L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Auto-switch to a connected Bluetooth headset mic (default ON), back to the phone mic when it disconnects. */
    fun autoBluetooth(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_BT, true)
    fun setAutoBluetooth(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_AUTO_BT, on).apply()

    /** Stop auto-switching for a while (the headset has no usable mic). */
    fun blockAutoBluetooth(ms: Long) { btBlockedUntil = System.currentTimeMillis() + ms }

    /** The microphone to use RIGHT NOW. */
    fun selected(c: Context): MicRoute {
        if (MicPrefs.useBluetoothMic(c)) return MicRoute.BLUETOOTH // user forced it
        if (autoBluetooth(c) && System.currentTimeMillis() >= btBlockedUntil &&
            BluetoothMicRouter.findInput(c) != null && btAllowed?.invoke() != false
        ) return MicRoute.BLUETOOTH
        val v = prefs(c).getString(KEY, null)
        return MicRoute.values().firstOrNull { it.name == v } ?: MicRoute.PHONE
    }

    /** Mic to ENROLL with: the Bluetooth mic if auto-switch is on and a headset mic is connected. */
    fun enrollRoute(c: Context): MicRoute {
        if (MicPrefs.useBluetoothMic(c)) return MicRoute.BLUETOOTH
        if (autoBluetooth(c) && BluetoothMicRouter.findInput(c) != null) return MicRoute.BLUETOOTH
        return selected(c)
    }

    fun set(c: Context, route: MicRoute) {
        prefs(c).edit().putString(KEY, route.name).apply()
        MicPrefs.setUseBluetoothMic(c, route == MicRoute.BLUETOOTH)
    }

    private fun inputs(c: Context): Array<AudioDeviceInfo> =
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)

    fun builtinMic(c: Context): AudioDeviceInfo? = inputs(c).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun wiredMic(c: Context): AudioDeviceInfo? = inputs(c).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
}
