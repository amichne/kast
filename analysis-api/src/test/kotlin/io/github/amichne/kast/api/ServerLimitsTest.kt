package io.github.amichne.kast.api

import io.github.amichne.kast.api.continuation.ContinuationCapacity
import io.github.amichne.kast.api.continuation.ContinuationTtl
import io.github.amichne.kast.api.contract.ServerLimits
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerLimitsTest {
    @Test
    fun `continuation policy crosses the wire as primitives and enters the store as types`() {
        val limits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 60_000,
            maxConcurrentRequests = 4,
            continuationTtlMillis = 1_234,
            continuationCapacity = 2,
        )

        assertEquals(ContinuationTtl.of(Duration.ofMillis(1_234)), limits.typedContinuationTtl)
        assertEquals(ContinuationCapacity.of(2), limits.typedContinuationCapacity)
    }

    @Test
    fun `continuation policy rejects zero or negative values at construction`() {
        assertThrows<IllegalArgumentException> {
            ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 60_000,
                maxConcurrentRequests = 4,
                continuationTtlMillis = 0,
            )
        }
        assertThrows<IllegalArgumentException> {
            ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 60_000,
                maxConcurrentRequests = 4,
                continuationCapacity = -1,
            )
        }
    }
}
