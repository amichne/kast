package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ServerLimits(
    val maxResults: Int,
    val requestTimeoutMillis: Long,
    val maxConcurrentRequests: Int,
)
