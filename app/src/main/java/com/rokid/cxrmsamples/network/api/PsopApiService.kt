package com.rokid.cxrmsamples.network.api

import com.rokid.cxrmsamples.network.models.AppendTerminalEventRequest
import com.rokid.cxrmsamples.network.models.CreateInvocationRequest
import com.rokid.cxrmsamples.network.models.InvocationListResponse
import com.rokid.cxrmsamples.network.models.InvocationResponse
import com.rokid.cxrmsamples.network.models.RunResponse
import com.rokid.cxrmsamples.network.models.SkillSummaryResponse
import com.rokid.cxrmsamples.network.models.TerminalEventAppendResponse
import com.rokid.cxrmsamples.network.models.TerminalEventResponse
import com.rokid.cxrmsamples.network.models.TerminalSessionResponse
import com.rokid.cxrmsamples.network.models.TraceEvent
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Query

interface PsopApiService {
    @POST("gateway/invocations")
    suspend fun createInvocation(@Body request: CreateInvocationRequest): InvocationResponse

    @GET("runs/{runId}")
    suspend fun getRun(@Path("runId") runId: String): RunResponse

    @GET("terminal/sessions/{runId}/events")
    suspend fun getTerminalEvents(
        @Path("runId") runId: String,
        @Query("from_seq") fromSeq: Int? = null,
        @Query("to_seq") toSeq: Int? = null
    ): List<TerminalEventResponse>

    @POST("terminal/sessions/{runId}/events")
    suspend fun appendTerminalEvent(
        @Path("runId") runId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: AppendTerminalEventRequest
    ): TerminalEventAppendResponse

    @Multipart
    @POST("terminal/sessions/{runId}/events")
    suspend fun uploadTerminalFile(
        @Path("runId") runId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Part("event") event: RequestBody,
        @Part file: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null,
        @Part("external_event_id") externalEventId: RequestBody? = null
    ): TerminalEventAppendResponse

    @GET("terminal/sessions/{runId}")
    suspend fun getTerminalSession(
        @Path("runId") runId: String
    ): TerminalSessionResponse

    @GET("runs/{runId}/trace-events")
    suspend fun getTraceEvents(
        @Path("runId") runId: String,
        @Query("event_type") eventType: String? = null
    ): List<TraceEvent>

    @GET("terminal/sessions/{runId}/events/{eventId}/content")
    @Streaming
    suspend fun downloadTerminalEventContent(
        @Path("runId") runId: String,
        @Path("eventId") eventId: String,
        @Header("Range") range: String? = null
    ): ResponseBody

    @GET("terminal/sessions/{runId}/events/{eventId}/parts/{partId}/content")
    @Streaming
    suspend fun downloadTerminalEventPartContent(
        @Path("runId") runId: String,
        @Path("eventId") eventId: String,
        @Path("partId") partId: String,
        @Header("Range") range: String? = null
    ): ResponseBody

    @GET("skills")
    suspend fun listSkills(@Query("is_published") isPublished: String? = null): List<SkillSummaryResponse>

    @GET("gateway/invocations")
    suspend fun listInvocations(@Query("skill_key") skillKey: String? = null): List<InvocationListResponse>
}
