package com.jarvis.assistant.core

import com.jarvis.assistant.speech.VoiceInteractionManager

enum class Connection { PHONE_READY, NO_AI, PC_ONLINE, PC_OFFLINE }

data class Stat(val label: String, val value: Int?)

data class UiState(
    val reply: String = "J.A.R.V.I.S. is online and ready. How may I assist you, sir?",
    val busy: Boolean = false,
    val mode: VoiceInteractionManager.Mode = VoiceInteractionManager.Mode.IDLE,
    val connection: Connection = Connection.NO_AI,
    val pcMode: Boolean = false,
    val note: String? = null,
    val audioRms: Float = 0f,
    /** Result of the last phone action that was really executed (volume, torch, call...). */
    val lastAction: String? = null,
    val stats: List<Stat> = listOf(Stat("BAT", null), Stat("RAM", null), Stat("DISK", null))
)
