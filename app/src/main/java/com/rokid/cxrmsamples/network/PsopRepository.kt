package com.rokid.cxrmsamples.network

import com.rokid.cxrmsamples.network.api.RetrofitClient
import com.rokid.cxrmsamples.network.models.AppendTerminalEventRequest
import com.rokid.cxrmsamples.network.models.CreateInvocationRequest
import com.rokid.cxrmsamples.network.models.InvocationResponse
import com.rokid.cxrmsamples.network.models.RunResponse
import com.rokid.cxrmsamples.network.models.TerminalEventAppendResponse
import com.rokid.cxrmsamples.network.models.TerminalEventResponse
import com.rokid.cxrmsamples.network.models.TerminalSessionResponse
import com.rokid.cxrmsamples.network.models.TraceEvent
import com.rokid.cxrmsamples.network.models.WebSocketEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.util.UUID

class PsopRepository {
    private val api = RetrofitClient.apiService
    private val wsManager = PsopWebSocketManager(RetrofitClient.okHttpClient, maxReconnectAttempts = 5)

    val wsEvents: SharedFlow<WebSocketEvent> get() = wsManager.events
    val connectionState: StateFlow<ConnectionState> get() = wsManager.connectionState
    val lastReceivedSeq: Int get() = wsManager.lastReceivedSeq

    /** 设置重连前检查回调 */
    fun setOnBeforeReconnect(callback: suspend (runId: String) -> Boolean) {
        wsManager.onBeforeReconnect = callback
    }

    /** 设置重连成功后事件恢复回调 */
    fun setOnReconnectRecoverEvents(callback: (runId: String, fromSeq: Int) -> Unit) {
        wsManager.onReconnectRecoverEvents = callback
    }

    suspend fun createInvocation(skillKey: String): InvocationResponse {
        val request = CreateInvocationRequest(skillKey = skillKey)
        try {
            return api.createInvocation(request)
        } catch (e: HttpException) {
            val url = e.response()?.raw()?.request?.url?.toString() ?: "unknown"
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            throw RuntimeException("[$code] $url\n$errorBody", e)
        }
    }

    suspend fun getRun(runId: String): RunResponse {
        return api.getRun(runId)
    }

    suspend fun getTerminalEvents(runId: String, fromSeq: Int? = null): List<TerminalEventResponse> {
        return api.getTerminalEvents(runId, fromSeq)
    }

    suspend fun recoverMissedEvents(runId: String, fromSeq: Int): List<TerminalEventResponse> {
        return api.getTerminalEvents(runId, fromSeq)
    }

    suspend fun appendTerminalEvent(runId: String, text: String): TerminalEventAppendResponse {
        val request = AppendTerminalEventRequest(
            payloadInline = text,
            externalEventId = "android-${UUID.randomUUID()}"
        )
        try {
            return api.appendTerminalEvent(runId, UUID.randomUUID().toString(), request)
        } catch (e: HttpException) {
            val url = e.response()?.raw()?.request?.url?.toString() ?: "unknown"
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            throw RuntimeException("[$code] $url\n$errorBody", e)
        }
    }

    fun connectWebSocket(runId: String) {
        wsManager.connect(runId)
    }

    fun disconnectWebSocket() {
        wsManager.disconnect()
    }

    suspend fun getTerminalSession(runId: String): TerminalSessionResponse {
        return api.getTerminalSession(runId)
    }

    suspend fun getTraceEvents(runId: String, eventType: String? = null): List<TraceEvent> {
        return api.getTraceEvents(runId, eventType)
    }

    suspend fun uploadTerminalFile(
        runId: String,
        file: File,
        caption: String? = null
    ): TerminalEventAppendResponse {
        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val requestFile = file.asRequestBody(mediaType)
        val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
        val captionBody = caption?.toRequestBody("text/plain".toMediaTypeOrNull())
        return api.uploadTerminalFile(
            runId = runId,
            idempotencyKey = UUID.randomUUID().toString(),
            file = filePart,
            caption = captionBody
        )
    }
}
