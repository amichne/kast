package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.client.fields.TelemetryScopes
import io.github.amichne.kast.api.client.fields.TelemetryEnabled
import io.github.amichne.kast.api.client.fields.TelemetryDetail
import io.github.amichne.kast.api.client.fields.ServerRequestTimeoutMillis
import io.github.amichne.kast.api.client.fields.ServerMaxConcurrentRequests
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ServerConfig
import io.github.amichne.kast.api.client.fields.ServerMaxResults
import io.github.amichne.kast.api.contract.ServerLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KastPluginServiceConfigTest {
    @Test
    fun `intellij server limits use config defaults`() {
        val limits = intellijServerLimits(KastConfig.defaults())

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
    fun `intellij server limits honor config overrides`() {
        val limits = intellijServerLimits(
            KastConfig.defaults().copy(
                server = ServerConfig(
                    maxResults = ServerMaxResults(42),
                    requestTimeoutMillis = ServerRequestTimeoutMillis(120_000L),
                    maxConcurrentRequests = ServerMaxConcurrentRequests(9),
                ),
            ),
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

    @Test
    fun `intellij telemetry uses config`() {
        val telemetry = IntelliJBackendTelemetry.fromConfig(
            workspaceRoot = Path.of("/tmp/workspace"),
            config = KastConfig.defaults().copy(
                telemetry = KastConfig.defaults().telemetry.copy(
                    enabled = TelemetryEnabled(true),
                    scopes = TelemetryScopes("references,rename"),
                    detail = TelemetryDetail("verbose"),
                ),
            ),
        )

        assertTrue(telemetry.isEnabled(IntelliJTelemetryScope.REFERENCES))
        assertTrue(telemetry.isEnabled(IntelliJTelemetryScope.RENAME))
        assertFalse(telemetry.isEnabled(IntelliJTelemetryScope.CALL_HIERARCHY))
        assertTrue(telemetry.isVerbose(IntelliJTelemetryScope.REFERENCES))
    }
}
