package com.jarvis.assistant.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ActivityContextTracker(
    private val onAppSwitched: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _currentApp = MutableStateFlow<String?> (null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    private var debounceJob: Job? = null
    private var lastEmittedApp: String? = null

    fun onForegroundPackageChanged(pkg: String) {
        if (pkg.isBlank() || pkg == "android" || pkg == "com.android.systemui") return
        _currentApp.value = pkg

        if (pkg == lastEmittedApp) return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (_currentApp.value == pkg && pkg != lastEmittedApp) {
                lastEmittedApp = pkg
                onAppSwitched(pkg)
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 20_000L
    }
}
