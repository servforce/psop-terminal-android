package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TerminalEventResponse(
    val id: String,
    @SerializedName("terminal_session_id") val terminalSessionId: String,
    @SerializedName("run_id") val runId: String,
    val direction: String,
    @SerializedName("event_kind") val eventKind: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("payload_inline") val payloadInline: Any?,
    @SerializedName("seq_no") val seqNo: Int,
    @SerializedName("external_event_id") val externalEventId: String?,
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("created_at") val createdAt: String
)
