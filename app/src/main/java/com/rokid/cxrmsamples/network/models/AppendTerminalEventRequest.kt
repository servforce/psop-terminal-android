package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class AppendTerminalEventRequest(
    val direction: String = "input",
    @SerializedName("event_kind") val eventKind: String = "terminal.multimodal.input.v1",
    @SerializedName("mime_type") val mimeType: String = "multipart/mixed",
    @SerializedName("payload_inline") val payloadInline: Any? = null,
    val text: String? = null,
    @SerializedName("artifact_object_id") val artifactObjectId: String? = null,
    @SerializedName("binding_id") val bindingId: String? = null,
    @SerializedName("occurred_at") val occurredAt: String? = null,
    val source: EventSource = EventSource(),
    @SerializedName("external_event_id") val externalEventId: String? = null
)
