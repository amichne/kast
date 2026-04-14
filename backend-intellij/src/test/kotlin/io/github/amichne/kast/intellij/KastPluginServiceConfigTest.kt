package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.ServerLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastPluginServiceConfigTest {
    @Test
    fun `intellij server limits use defaults when env is missing or invalid`() {
        val limits = intellijServerLimits(
            getenv = mapOf(
                "KAST_INTELLIJ_MAX_CONCURRENT" to "nope",
                "KAST_INTELLIJ_TIMEOUT_MS" to null,
                "KAST_INTELLIJ_MAX_RESULTS" to "",
            )::get,
        )

        assertEquals(
            ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 30_000L,
                maxConcurrentRequests = 4,
            ),
            limits,
        )
    }

    @Test
    fun `intellij server limits honor valid env overrides`() {
        val limits = intellijServerLimits(
            getenv = mapOf(
                "KAST_INTELLIJ_MAX_CONCURRENT" to "9",
                "KAST_INTELLIJ_TIMEOUT_MS" to "120000",
                "KAST_INTELLIJ_MAX_RESULTS" to "42",
            )::get,
        )

        assertEquals(
            ServerLimits(
                maxResults = 42,
                requestTimeoutMillis = 120_000L,
                maxConcurrentRequests = 9,
            ),
            limits,
        )
    }
}
