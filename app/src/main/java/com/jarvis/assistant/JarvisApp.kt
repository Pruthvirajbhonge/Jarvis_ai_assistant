package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.core.JarvisCore

class JarvisApp : Application() {
    lateinit var core: JarvisCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = JarvisCore(this)
    }
}
