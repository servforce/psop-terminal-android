package com.rokid.cxrmsamples.network

import com.rokid.cxrmsamples.network.api.RetrofitClient
import com.rokid.cxrmsamples.network.models.AppendTerminalEventRequest
import com.rokid.cxrmsamples.network.models.CreateInvocationRequest
import com.rokid.cxrmsamples.network.models.EventSource
import com.rokid.cxrmsamples.network.models.InvocationListResponse
import com.rokid.cxrmsamples.network.models.InvocationResponse
import com.rokid.cxrmsamples.network.models.RunResponse
import com.rokid.cxrmsamples.network.models.SkillSummaryResponse
import com.rokid.cxrmsamples.network.models.TerminalEventAppendResponse
import com.rokid.cxrmsamples.network.models.TerminalEventResponse
import com.rokid.cxrmsamples.network.models.TerminalSessionResponse
import com.rokid.cxrmsamples.network.models.TraceEvent
import com.rokid.cxrmsamples.network.models.WebSocketEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
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
        android.util.Log.e("PSOP_DEBUG", ">>> appendTerminalEvent ENTER, runId=$runId, text='$text'")
        val request = AppendTerminalEventRequest(
            text = text,
            payloadInline = text,
            source = EventSource(kind = "android"),
            externalEventId = "android-${UUID.randomUUID()}"
        )
        android.util.Log.e("PSOP_DEBUG", ">>> request.text=${request.text}, request.payloadInline=${request.payloadInline}")
        try {
            val result = api.appendTerminalEvent(runId, UUID.randomUUID().toString(), request)
            android.util.Log.e("PSOP_DEBUG", ">>> appendTerminalEvent SUCCESS, eventId=${result.eventId}")
            return result
        } catch (e: HttpException) {
            val url = e.response()?.raw()?.request?.url?.toString() ?: "unknown"
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            android.util.Log.e("PSOP_DEBUG", ">>> appendTerminalEvent FAILED: [$code] $url $errorBody")
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
        idempotencyKey: String,
        event: RequestBody,
        file: MultipartBody.Part,
        caption: RequestBody? = null,
        externalEventId: RequestBody? = null
    ): TerminalEventAppendResponse {
        return api.uploadTerminalFile(
            runId = runId,
            idempotencyKey = idempotencyKey,
            event = event,
            file = file,
            caption = caption,
            externalEventId = externalEventId
        )
    }

    suspend fun downloadTerminalEventContent(runId: String, eventId: String): ResponseBody {
        return RetrofitClient.apiService.downloadTerminalEventContent(runId, eventId)
    }

    suspend fun downloadTerminalEventPartContent(runId: String, eventId: String, partId: String): ResponseBody {
        return RetrofitClient.apiService.downloadTerminalEventPartContent(runId, eventId, partId)
    }

    suspend fun listSkills(): List<SkillSummaryResponse> {
        return api.listSkills(isPublished = "true")
    }

    suspend fun listInvocations(skillKey: String): List<InvocationListResponse> {
        return api.listInvocations(skillKey = skillKey)
    }
}
