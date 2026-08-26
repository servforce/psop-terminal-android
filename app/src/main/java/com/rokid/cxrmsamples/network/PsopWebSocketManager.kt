package com.rokid.cxrmsamples.network

import com.google.gson.Gson
import com.rokid.cxrmsamples.network.models.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

class PsopWebSocketManager(
    private val client: OkHttpClient = OkHttpClient(),
    private val maxReconnectAttempts: Int = 5
) {
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var currentRunId: String? = null
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var connectionToken = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var lastReceivedSeq: Int = 0
        private set

    /** 重连恢复回调，上层设置后在重连成功时被调用以补齐缺失事件 */
    var onReconnectRecoverEvents: ((runId: String, fromSeq: Int) -> Unit)? = null

    /** 重连前检查回调，返回 true 表示允许重连，false 表示停止重连 */
    var onBeforeReconnect: (suspend (runId: String) -> Boolean)? = null

    private val _events = MutableSharedFlow<WebSocketEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect(runId: String) {
        disconnect()
        currentRunId = runId
        lastReceivedSeq = 0
        reconnectAttempts = 0
        doConnect(runId)
    }

    private fun doConnect(runId: String) {
        val token = ++connectionToken
        _connectionState.value = ConnectionState.CONNECTING
        val url = "${PsopConfig.wsUrl}/runs/$runId"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentConnection(runId, token)) return
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0

                // 断线重连后补齐缺失事件
                if (lastReceivedSeq > 0) {
                    scope.launch {
                        try {
                            onReconnectRecoverEvents?.invoke(runId, lastReceivedSeq + 1)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrentConnection(runId, token)) return
                try {
                    // DEBUG: 打印所有 WebSocket 消息
                    android.util.Log.d("PSOP_DEBUG", "WS RAW [${text.length}chars]: ${text.take(500)}")
                    val event = gson.fromJson(text, WebSocketEvent::class.java)
                    lastReceivedSeq = maxOf(lastReceivedSeq, event.seqNo)
                    scope.launch { _events.emit(event) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrentConnection(runId, token)) return
                _connectionState.value = ConnectionState.RECONNECTING
                scheduleReconnect(runId, token)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentConnection(runId, token)) return
                _connectionState.value = ConnectionState.RECONNECTING
                scheduleReconnect(runId, token)
            }
        })
    }

    private fun isCurrentConnection(runId: String, token: Long): Boolean {
        return currentRunId == runId && connectionToken == token
    }

    private fun scheduleReconnect(runId: String, token: Long) {
        if (!isCurrentConnection(runId, token)) return
        if (reconnectAttempts >= maxReconnectAttempts) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = minOf(30000L, 1000L * (1 shl minOf(reconnectAttempts, 4)))
            reconnectAttempts++
            delay(delayMs)
            if (!isCurrentConnection(runId, token)) return@launch
            // 重连前先检查 Run 状态
            val shouldReconnect = try {
                onBeforeReconnect?.invoke(runId) ?: true
            } catch (e: Exception) {
                true // 检查失败默认尝试重连
            }
            if (shouldReconnect && isCurrentConnection(runId, token)) {
                doConnect(runId)
            } else if (isCurrentConnection(runId, token)) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun disconnect() {
        ++connectionToken
        reconnectJob?.cancel()
        reconnectJob = null
        val socket = webSocket
        webSocket = null
        currentRunId = null
        lastReceivedSeq = 0
        _connectionState.value = ConnectionState.DISCONNECTED
        socket?.close(1000, "Client disconnect")
    }
}
