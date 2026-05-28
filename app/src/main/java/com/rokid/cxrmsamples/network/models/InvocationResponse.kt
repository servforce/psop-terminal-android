package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class InvocationResponse(
    val id: String,
    @SerializedName("skill_definition_id") val skillDefinitionId: String,
    @SerializedName("skill_version_id") val skillVersionId: String,
    @SerializedName("compile_artifact_id") val compileArtifactId: String,
    @SerializedName("gateway_type") val gatewayType: String,
    @SerializedName("input_envelope") val inputEnvelope: Map<String, Any>,
    @SerializedName("terminal_context") val terminalContext: Map<String, Any>,
    val status: String,
    @SerializedName("run_id") val runId: String?,
    @SerializedName("terminal_session_id") val terminalSessionId: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("binding_preferences") val bindingPreferences: List<Any>? = null
)
