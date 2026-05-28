package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class CreateInvocationRequest(
    @SerializedName("skill_key") val skillKey: String,
    @SerializedName("version_selector") val versionSelector: String = "latest",
    @SerializedName("gateway_type") val gatewayType: String = "terminal",
    @SerializedName("terminal_context") val terminalContext: Map<String, Any> = mapOf(
        "terminal_kind" to "android",
        "supported_inputs" to listOf(
            "terminal.text.input.v1",
            "terminal.image.input.v1"
        ),
        "device_id" to "",
        "operator" to ""
    ),
    @SerializedName("input_envelope") val inputEnvelope: Map<String, Any> = emptyMap()
)
