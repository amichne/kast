@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.continuation.ContinuationCapacity
import io.github.amichne.kast.api.continuation.ContinuationTtl
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import java.time.Duration
import kotlinx.serialization.Serializable

@Serializable
data class ServerLimits(
    @DocField(description = "Maximum number of results the server will return per request.")
    val maxResults: Int,
    @DocField(description = "Server-side timeout for individual requests in milliseconds.")
    val requestTimeoutMillis: Long,
    @DocField(description = "Maximum number of requests the server will process concurrently.")
    val maxConcurrentRequests: Int,
    @DocField(description = "Maximum time in milliseconds to spend walking a single candidate file during reference search. Files whose PSI walk exceeds this budget are skipped.")
    val perFileScanBudgetMillis: Long = 5_000,
    @DocField(description = "Time in milliseconds before an unused server-held continuation expires.", defaultValue = "60000")
    val continuationTtlMillis: Long = 60_000,
    @DocField(description = "Maximum server-held continuations retained by one typed store.", defaultValue = "256")
    val continuationCapacity: Int = 256,
) {
    val typedContinuationTtl: ContinuationTtl
        get() = ContinuationTtl.of(Duration.ofMillis(continuationTtlMillis))

    val typedContinuationCapacity: ContinuationCapacity
        get() = ContinuationCapacity.of(continuationCapacity)

    init {
        require(continuationTtlMillis > 0) { "Continuation time to live must be positive" }
        require(continuationCapacity > 0) { "Continuation capacity must be positive" }
    }
}
