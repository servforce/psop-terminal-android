package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TaskStatusResponse(
    @SerializedName("run_id") val runId: String,
    @SerializedName("snapshot_seq") val snapshotSeq: Int = 0,
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("run_status") val runStatus: String = "",
    @SerializedName("activity_status") val activityStatus: String = "",
    @SerializedName("task") val task: TaskInfo? = null,
    @SerializedName("progress") val progress: TaskProgress? = null,
    @SerializedName("current_stage_id") val currentStageId: String? = null,
    @SerializedName("stages") val stages: List<TaskStage> = emptyList(),
    @SerializedName("current_checkpoint") val currentCheckpoint: TaskCheckpoint? = null
)

data class TaskInfo(
    @SerializedName("skill_key") val skillKey: String = "",
    @SerializedName("skill_name") val skillName: String = "",
    @SerializedName("version_no") val versionNo: Int = 0,
    @SerializedName("execution_goal") val executionGoal: String = ""
)

data class TaskProgress(
    @SerializedName("completed") val completed: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("percent") val percent: Int = 0
)

data class TaskStage(
    @SerializedName("id") val id: String = "",
    @SerializedName("index") val index: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("goal") val goal: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("status_reason") val statusReason: String = ""
)

data class TaskCheckpoint(
    @SerializedName("checkpoint_id") val checkpointId: String = "",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("expected_inputs") val expectedInputs: List<Map<String, String>> = emptyList(),
    @SerializedName("accepted_requirements") val acceptedRequirements: Int = 0,
    @SerializedName("total_requirements") val totalRequirements: Int = 0,
    @SerializedName("requirements") val requirements: List<CheckpointRequirement> = emptyList()
)

data class CheckpointRequirement(
    @SerializedName("requirement_key") val requirementKey: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("kind") val kind: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("reason") val reason: String = ""
)
