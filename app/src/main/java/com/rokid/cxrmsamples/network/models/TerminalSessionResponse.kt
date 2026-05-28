package com.rokid.cxrmsamples.network.models

import com.google.gson.annotations.SerializedName

data class TerminalSessionResponse(
    @SerializedName("terminal_session") val terminalSession: TerminalSession,
    @SerializedName("transcript_summary") val transcriptSummary: TranscriptSummary
)

data class TerminalSession(
    val id: String,
    @SerializedName("run_id") val runId: String,
    val mode: String?,
    val status: String,
    @SerializedName("opened_at") val openedAt: String,
    @SerializedName("closed_at") val closedAt: String?,
    @SerializedName("created_at") val createdAt: String
)

data class TranscriptSummary(
    @SerializedName("latest_seq") val latestSeq: Int,
    @SerializedName("event_count") val eventCount: Int
)
