package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TerminalEventResponse(
    val id: String,
    @SerializedName("terminal_session_id") val terminalSessionId: String,
    @SerializedName("run_id") val runId: String,
    @SerializedName("artifact_object_id") val artifactObjectId: String? = null,
    @SerializedName("source_ref") val sourceRef: Map<String, Any>? = null,
    @SerializedName("trace_event_id") val traceEventId: String? = null,
    @SerializedName("run_capability_binding_id") val runCapabilityBindingId: String? = null,
    val direction: String,
    @SerializedName("event_kind") val eventKind: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("payload_inline") val payloadInline: Any?,
    @SerializedName("seq_no") val seqNo: Int,
    @SerializedName("external_event_id") val externalEventId: String?,
    @SerializedName("occurred_at") val occurredAt: String,
    val parts: List<TerminalEventPartResponse> = emptyList(),
    @SerializedName("created_at") val createdAt: String
)
