package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class RunResponse(
    val id: String,
    @SerializedName("invocation_id") val invocationId: String,
    @SerializedName("skill_definition_id") val skillDefinitionId: String,
    val status: String,
    @SerializedName("runtime_phase") val runtimePhase: String? = null,
    @SerializedName("latest_terminal_seq") val latestTerminalSeq: Int = 0,
    @SerializedName("current_step") val currentStep: String? = null,
    @SerializedName("wait_reason") val waitReason: String? = null,
    @SerializedName("expected_inputs") val expectedInputs: List<Map<String, Any>>? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("latest_trace_seq") val latestTraceSeq: Int? = null,
    @SerializedName("terminal_session_id") val terminalSessionId: String? = null,
    @SerializedName("final_output") val finalOutput: Any? = null,
    @SerializedName("exit_reason") val exitReason: String? = null,
    @SerializedName("checkpoint_id") val checkpointId: String? = null,
    @SerializedName("resume_phase") val resumePhase: String? = null,
    @SerializedName("latest_evaluation") val latestEvaluation: Any? = null
)
