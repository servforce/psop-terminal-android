package com.rokid.cxrmsamples.network.models

/** 服务端 GET /runs 的分页结果。 */
data class RunPageResponse(
    val items: List<RunResponse>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)
