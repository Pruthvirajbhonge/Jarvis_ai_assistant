package com.jarvis.assistant.data.model

data class BrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val iconResId: Int = 0,
    val isActive: Boolean = false
)
