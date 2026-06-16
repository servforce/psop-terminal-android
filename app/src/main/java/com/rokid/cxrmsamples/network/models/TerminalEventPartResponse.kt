package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TerminalEventPartResponse(
    val id: String,
    @SerializedName("terminal_event_id") val terminalEventId: String,
    @SerializedName("run_id") val runId: String,
    @SerializedName("artifact_object_id") val artifactObjectId: String? = null,
    @SerializedName("part_id") val partId: String,
    @SerializedName("order_index") val orderIndex: Int,
    val kind: String,
    @SerializedName("mime_type") val mimeType: String,
    val text: String = "",
    @SerializedName("size_bytes") val sizeBytes: Int = 0,
    val checksum: String = "",
    val metadata: Map<String, Any>? = null,
    @SerializedName("created_at") val createdAt: String
)
