package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class WebSocketEvent(
    @SerializedName("event_type") val eventType: String,
    @SerializedName("run_id") val runId: String,
    @SerializedName("invocation_id") val invocationId: String?,
    @SerializedName("seq_no") val seqNo: Int,
    @SerializedName("occurred_at") val occurredAt: String?,
    val payload: Map<String, Any?>?
)
