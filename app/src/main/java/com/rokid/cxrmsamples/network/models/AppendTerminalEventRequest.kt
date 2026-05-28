package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class AppendTerminalEventRequest(
    val direction: String = "input",
    @SerializedName("event_kind") val eventKind: String = "terminal.text.input.v1",
    @SerializedName("mime_type") val mimeType: String = "text/plain",
    @SerializedName("payload_inline") val payloadInline: String,
    val source: EventSource = EventSource(),
    @SerializedName("external_event_id") val externalEventId: String? = null
)
