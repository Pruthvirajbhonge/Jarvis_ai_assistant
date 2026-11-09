package com.jarvis.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.jarvis.assistant.core.AppState
import com.jarvis.assistant.core.JarvisCore

class JarvisApp : Application() {
    lateinit var core: JarvisCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = JarvisCore(this)

        // Tracks whether a Jarvis screen is visible (launching other apps is always allowed then).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                AppState.inForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) AppState.inForeground = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
