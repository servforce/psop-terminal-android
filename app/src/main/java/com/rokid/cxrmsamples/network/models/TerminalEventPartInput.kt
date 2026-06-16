package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TerminalEventPartInput(
    @SerializedName("part_id") val partId: String? = null,
    val kind: String = "text",
    @SerializedName("mime_type") val mimeType: String = "text/plain",
    val text: String? = null,
    @SerializedName("artifact_object_id") val artifactObjectId: String? = null,
    @SerializedName("size_bytes") val sizeBytes: Int? = null,
    val checksum: String? = null,
    val metadata: Map<String, Any>? = null
)
