package com.jarvis.assistant.data.api

/**
 * The ONLY place that knows the Python backend's route names.
 * If your FastAPI routes differ, change these three strings.
 */
object ApiConfig {
    const val COMMAND_PATH = "api/command"   // POST {prompt, device, client_speaks} -> {reply, ...}
    const val STATUS_PATH = "api/status"     // GET  -> health + (optional) cpu/ram/disk
    const val PHONE_WS_PATH = "ws/phone"     // WebSocket for phone tasks + live metrics
}
