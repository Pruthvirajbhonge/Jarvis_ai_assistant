package com.jarvis.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.core.UiState
import com.jarvis.assistant.data.prefs.JarvisSettings
import com.jarvis.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val core = (app as JarvisApp).core

    val state: StateFlow<UiState> = core.state
    val settings: SettingsStore get() = core.settings

    fun submit(text: String) = core.handleCommand(text)
    fun listen() = core.voice.startCommandSession()
    fun setPcMode(on: Boolean) = core.setPcMode(on)
    suspend fun testAi(s: JarvisSettings): String = core.testAi(s)
}
