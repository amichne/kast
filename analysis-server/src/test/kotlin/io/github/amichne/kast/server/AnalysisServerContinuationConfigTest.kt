package io.github.amichne.kast.server

import io.github.amichne.kast.api.continuation.ContinuationCapacity
import io.github.amichne.kast.api.continuation.ContinuationTtl
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AnalysisServerContinuationConfigTest {
    @Test
    fun `continuation policy requires positive ttl and capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisServerConfig(continuationTtlMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisServerConfig(continuationCapacity = 0)
        }
    }

    @Test
    fun `typed continuation policy preserves configured values`() {
        val config = AnalysisServerConfig(
            continuationTtlMillis = 1_234,
            continuationCapacity = 7,
        )

        assertEquals(ContinuationTtl.of(Duration.ofMillis(1_234)), config.typedContinuationTtl)
        assertEquals(ContinuationCapacity.of(7), config.typedContinuationCapacity)
    }
}
