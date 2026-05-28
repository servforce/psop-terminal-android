package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TraceEvent(
    val id: String,
    @SerializedName("run_id") val runId: String,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("seq_no") val seqNo: Int,
    val payload: Map<String, Any>?,
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("created_at") val createdAt: String
)
