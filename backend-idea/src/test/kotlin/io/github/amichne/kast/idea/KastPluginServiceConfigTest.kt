package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.fields.TelemetryScopes
import io.github.amichne.kast.api.client.fields.TelemetryEnabled
import io.github.amichne.kast.api.client.fields.TelemetryDetail
import io.github.amichne.kast.api.client.fields.PathsDescriptorDir
import io.github.amichne.kast.api.client.fields.ServerRequestTimeoutMillis
import io.github.amichne.kast.api.client.fields.ServerMaxConcurrentRequests
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.ServerConfig
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.client.fields.ServerMaxResults
import io.github.amichne.kast.api.contract.ServerLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KastPluginServiceConfigTest {
    @Test
    fun `idea server limits use config defaults`() {
        val limits = ideaServerLimits(KastConfig.defaults())

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
    fun `idea server limits honor config overrides`() {
        val limits = ideaServerLimits(
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
    fun `idea config loader returns loaded config when config is valid`() {
        val workspaceRoot = Path.of("/tmp/workspace")
        val expectedConfig = KastConfig.defaults().copy(
            server = KastConfig.defaults().server.copy(
                maxResults = ServerMaxResults(42),
            ),
        )

        val config = loadIdeaKastConfig(
            workspaceRoot = workspaceRoot,
            loader = { path ->
                assertEquals(workspaceRoot, path)
                expectedConfig
            },
            reportFailure = { _, error -> error("Unexpected config load failure: $error") },
        )

        assertEquals(42, config.server.maxResults.value)
    }

    @Test
    fun `idea config loader falls back to defaults when config is invalid`() {
        val workspaceRoot = Path.of("/tmp/workspace")
        var reportedWorkspace: Path? = null
        var reportedError: Exception? = null

        val config = loadIdeaKastConfig(
            workspaceRoot = workspaceRoot,
            loader = { error("Invalid Kast config line 1 in config.toml") },
            reportFailure = { path, error ->
                reportedWorkspace = path
                reportedError = error
            },
        )

        assertEquals(KastConfig.defaults(), config)
        assertEquals(
            ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 30_000L,
                maxConcurrentRequests = 4,
            ),
            ideaServerLimits(config),
        )
        assertEquals(workspaceRoot, reportedWorkspace)
        assertTrue(reportedError?.message?.contains("Invalid Kast config") == true)
    }

    @Test
    fun `config reload restarts backend only when effective config changes`() {
        val defaults = KastConfig.defaults()
        val changed = defaults.copy(
            server = defaults.server.copy(
                maxResults = ServerMaxResults(42),
            ),
        )

        assertEquals(KastConfigReloadDecision.UNCHANGED, configReloadDecision(defaults, defaults))
        assertEquals(KastConfigReloadDecision.RESTART_BACKEND, configReloadDecision(defaults, changed))
    }

    @Test
    fun `idea telemetry uses config`() {
        val telemetry = IdeaBackendTelemetry.fromConfig(
            workspaceRoot = Path.of("/tmp/workspace"),
            config = KastConfig.defaults().copy(
                telemetry = KastConfig.defaults().telemetry.copy(
                    enabled = TelemetryEnabled(true),
                    scopes = TelemetryScopes("references,rename"),
                    detail = TelemetryDetail("verbose"),
                ),
            ),
        )

        assertTrue(telemetry.isEnabled(IdeaTelemetryScope.REFERENCES))
        assertTrue(telemetry.isEnabled(IdeaTelemetryScope.RENAME))
        assertFalse(telemetry.isEnabled(IdeaTelemetryScope.CALL_HIERARCHY))
        assertTrue(telemetry.isVerbose(IdeaTelemetryScope.REFERENCES))
    }

    @Test
    fun `idea server writes descriptors to configured descriptor directory`() {
        val descriptorDirectory = Path.of("build/kast-test/workspace-daemons")
        val socketPath = Path.of("build/kast-test/kast.sock")
        val limits = ServerLimits(
            maxResults = 42,
            requestTimeoutMillis = 120_000L,
            maxConcurrentRequests = 2,
        )
        val config = KastConfig.defaults().copy(
            paths = KastConfig.defaults().paths.copy(
                descriptorDir = PathsDescriptorDir(descriptorDirectory.toString()),
            ),
        )

        val serverConfig = ideaAnalysisServerConfig(socketPath, limits, config)

        assertEquals(AnalysisTransport.UnixDomainSocket(socketPath), serverConfig.transport)
        assertEquals(descriptorDirectory.toAbsolutePath().normalize(), serverConfig.descriptorDirectory)
    }
}
