package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TerminalEventAppendResponse(
    val accepted: Boolean,
    @SerializedName("event_id") val eventId: String,
    @SerializedName("seq_no") val seqNo: Int,
    val event: TerminalEventResponse
)
