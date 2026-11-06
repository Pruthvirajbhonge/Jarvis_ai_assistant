package com.jarvis.assistant.core

import android.os.Build
import com.jarvis.assistant.data.api.ApiConfig
import com.jarvis.assistant.data.model.JarvisJson
import com.jarvis.assistant.data.model.Metrics
import com.jarvis.assistant.data.model.PhoneTask
import com.jarvis.assistant.data.model.parsePhoneAction
import com.jarvis.assistant.data.model.str
import com.jarvis.assistant.data.model.toMetrics
import com.jarvis.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Bidirectional link to ws://<PC>:8000/ws/phone. Receives phone tasks and live metrics,
 * acks every task, and reconnects with exponential backoff.
 */
class PcSocketClient(
    http: OkHttpClient,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onTask: suspend (PhoneTask) -> String,
    private val onMetrics: (Metrics) -> Unit,
    private val onConnection: (Boolean) -> Unit
) {
    private val client = http.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                val closed = CompletableDeferred<Unit>()
                var socket: WebSocket? = null
                try {
                    val s = settings.current()
                    val request = Request.Builder()
                        .url(s.wsUrl(ApiConfig.PHONE_WS_PATH))
                        .apply { s.authHeader()?.let { header("Authorization", it) } }
                        .build()
                    socket = client.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            backoff = 2_000L
                            onConnection(true)
                            webSocket.send(buildJsonObject {
                                put("type", "hello")
                                put("device", "android")
                                put("name", Build.MODEL)
                            }.toString())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            scope.launch { handle(webSocket, text) }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            closed.complete(Unit)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            closed.complete(Unit)
                        }
                    })
                    closed.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // bad host etc.; retry after backoff
                } finally {
                    socket?.cancel()
                    onConnection(false)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun restart() {
        stop()
        start()
    }

    private suspend fun handle(ws: WebSocket, text: String) {
        val obj = try {
            JarvisJson.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return

        if (obj.str("type", "event")?.lowercase() == "ping") {
            ws.send("""{"type":"pong"}""")
            return
        }

        val payload = (obj["task"] as? JsonObject)
            ?: (obj["data"] as? JsonObject)?.takeIf { it["action"] != null }
            ?: obj

        val action = parsePhoneAction(payload["action"], payload["params"])
        if (action != null) {
            val id = payload.str("id", "task_id") ?: UUID.randomUUID().toString()
            val task = PhoneTask(id, action, payload.str("reply", "speech").orEmpty())
            val result = try {
                onTask(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "failed: ${e.message}"
            }
            ws.send(buildJsonObject {
                put("type", "ack")
                put("id", id)
                put("status", "done")
                put("result", result)
            }.toString())
            return
        }

        obj.toMetrics()?.let(onMetrics)
    }
}
