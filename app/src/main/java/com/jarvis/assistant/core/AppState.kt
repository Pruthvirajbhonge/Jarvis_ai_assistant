package com.jarvis.assistant.core

/** Tiny process-wide flags used to decide how an app/activity can be launched. */
object AppState {
    /** True while any Jarvis screen is visible. Launching other apps always works then. */
    @Volatile var inForeground = false

    /** True while the floating orb (a visible overlay window) is on screen. */
    @Volatile var overlayVisible = false
}
