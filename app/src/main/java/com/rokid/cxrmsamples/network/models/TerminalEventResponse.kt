package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TerminalEventResponse(
    val id: String,
    @SerializedName("terminal_session_id") val terminalSessionId: String,
    @SerializedName("run_id") val runId: String,
    // 文档：终端侧应忽略此字段，平台内部使用
    @SerializedName("artifact_object_id") val artifactObjectId: String? = null,
    // 文档：终端侧应忽略此字段，平台内部使用
    @SerializedName("source_ref") val sourceRef: Map<String, Any>? = null,
    // 文档：终端侧应忽略此字段，平台内部使用
    @SerializedName("trace_event_id") val traceEventId: String? = null,
    // 文档：终端侧应忽略此字段，平台内部使用
    @SerializedName("run_capability_binding_id") val runCapabilityBindingId: String? = null,
    val direction: String,
    @SerializedName("event_kind") val eventKind: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("payload_inline") val payloadInline: Any?,
    @SerializedName("seq_no") val seqNo: Int,
    @SerializedName("external_event_id") val externalEventId: String?,
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("parts") val parts: List<TerminalEventPartResponse> = emptyList(),
    @SerializedName("created_at") val createdAt: String
)
