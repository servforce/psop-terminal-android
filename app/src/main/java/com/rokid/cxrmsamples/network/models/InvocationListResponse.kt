package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class InvocationListResponse(
    val id: String,
    @SerializedName("skill_definition_id") val skillDefinitionId: String,
    val status: String,
    @SerializedName("run_id") val runId: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)
