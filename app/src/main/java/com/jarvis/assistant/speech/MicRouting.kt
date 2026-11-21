package com.jarvis.assistant.speech

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Which physical microphone JARVIS listens to. ONE choice, used for enrollment AND verification. */
enum class MicRoute { PHONE, WIRED, BLUETOOTH }

object MicRouting {
    private const val FILE = "jarvis_mic"
    private const val KEY = "route"

    /** Default = PHONE (the phone's built-in mic). Headphones are output-only unless explicitly selected. */
    fun selected(c: Context): MicRoute {
        if (MicPrefs.useBluetoothMic(c)) return MicRoute.BLUETOOTH // existing Settings switch
        val v = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        return MicRoute.values().firstOrNull { it.name == v } ?: MicRoute.PHONE
    }

    fun set(c: Context, route: MicRoute) {
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, route.name).apply()
        MicPrefs.setUseBluetoothMic(c, route == MicRoute.BLUETOOTH)
    }

    private fun inputs(c: Context): Array<AudioDeviceInfo> =
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)

    fun builtinMic(c: Context): AudioDeviceInfo? = inputs(c).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun wiredMic(c: Context): AudioDeviceInfo? = inputs(c).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
}
