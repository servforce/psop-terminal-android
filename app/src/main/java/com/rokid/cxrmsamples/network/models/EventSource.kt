package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class EventSource(
    val kind: String = "android",
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("connection_id") val connectionId: String? = null
)
