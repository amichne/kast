@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ServerLimits(
    @DocField(description = "Maximum number of results the server will return per request.")
    val maxResults: Int,
    @DocField(description = "Server-side timeout for individual requests in milliseconds.")
    val requestTimeoutMillis: Long,
    @DocField(description = "Maximum number of requests the server will process concurrently.")
    val maxConcurrentRequests: Int,
)
