package io.github.amichne.kast.standalone

import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryConfig
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryDetail
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StandaloneTelemetryConfigTest {
    @TempDir
    lateinit var workspaceRoot: Path

    // --- Scope parsing ---

    @Test
    fun `parse recognizes rename scope`() {
        assertEquals(StandaloneTelemetryScope.RENAME, StandaloneTelemetryScope.parse("rename"))
    }

    @Test
    fun `parse recognizes call-hierarchy variants`() {
        assertEquals(StandaloneTelemetryScope.CALL_HIERARCHY, StandaloneTelemetryScope.parse("call-hierarchy"))
        assertEquals(StandaloneTelemetryScope.CALL_HIERARCHY, StandaloneTelemetryScope.parse("call_hierarchy"))
        assertEquals(StandaloneTelemetryScope.CALL_HIERARCHY, StandaloneTelemetryScope.parse("callhierarchy"))
    }

    @Test
    fun `parse recognizes references scope variants`() {
        assertEquals(StandaloneTelemetryScope.REFERENCES, StandaloneTelemetryScope.parse("references"))
        assertEquals(StandaloneTelemetryScope.REFERENCES, StandaloneTelemetryScope.parse("find-references"))
        assertEquals(StandaloneTelemetryScope.REFERENCES, StandaloneTelemetryScope.parse("find_references"))
    }

    @Test
    fun `parse recognizes symbol-resolve scope variants`() {
        assertEquals(StandaloneTelemetryScope.SYMBOL_RESOLVE, StandaloneTelemetryScope.parse("symbol-resolve"))
        assertEquals(StandaloneTelemetryScope.SYMBOL_RESOLVE, StandaloneTelemetryScope.parse("symbol_resolve"))
        assertEquals(StandaloneTelemetryScope.SYMBOL_RESOLVE, StandaloneTelemetryScope.parse("symbolresolve"))
        assertEquals(StandaloneTelemetryScope.SYMBOL_RESOLVE, StandaloneTelemetryScope.parse("resolve"))
    }

    @Test
    fun `parse recognizes workspace-discovery scope variants`() {
        assertEquals(StandaloneTelemetryScope.WORKSPACE_DISCOVERY, StandaloneTelemetryScope.parse("workspace-discovery"))
        assertEquals(StandaloneTelemetryScope.WORKSPACE_DISCOVERY, StandaloneTelemetryScope.parse("workspace_discovery"))
        assertEquals(StandaloneTelemetryScope.WORKSPACE_DISCOVERY, StandaloneTelemetryScope.parse("workspacediscovery"))
        assertEquals(StandaloneTelemetryScope.WORKSPACE_DISCOVERY, StandaloneTelemetryScope.parse("discovery"))
    }

    @Test
    fun `parse returns null for unknown scope`() {
        assertNull(StandaloneTelemetryScope.parse("unknown"))
        assertNull(StandaloneTelemetryScope.parse(""))
    }

    @Test
    fun `parse is case-insensitive`() {
        assertEquals(StandaloneTelemetryScope.REFERENCES, StandaloneTelemetryScope.parse("REFERENCES"))
        assertEquals(StandaloneTelemetryScope.REFERENCES, StandaloneTelemetryScope.parse("References"))
    }

    // --- KAST_DEBUG support ---

    @Test
    fun `fromEnvironment with KAST_DEBUG enables all scopes and verbose detail`() {
        val telemetry = StandaloneTelemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader("KAST_DEBUG" to "true"),
        )

        StandaloneTelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isEnabled(scope), "Expected scope $scope to be enabled with KAST_DEBUG")
            assertTrue(telemetry.isVerbose(scope), "Expected scope $scope to be verbose with KAST_DEBUG")
        }
    }

    @Test
    fun `fromEnvironment with KAST_DEBUG=1 enables all scopes`() {
        val telemetry = StandaloneTelemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader("KAST_DEBUG" to "1"),
        )

        StandaloneTelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isEnabled(scope), "Expected scope $scope to be enabled with KAST_DEBUG=1")
        }
    }

    @Test
    fun `fromEnvironment without any env vars returns disabled telemetry`() {
        val telemetry = StandaloneTelemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(),
        )

        StandaloneTelemetryScope.entries.forEach { scope ->
            assertFalse(telemetry.isEnabled(scope), "Expected scope $scope to be disabled")
        }
    }

    @Test
    fun `fromEnvironment with KAST_OTEL_ENABLED and specific scopes`() {
        val telemetry = StandaloneTelemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(
                "KAST_OTEL_ENABLED" to "true",
                "KAST_OTEL_SCOPES" to "references,rename",
            ),
        )

        assertTrue(telemetry.isEnabled(StandaloneTelemetryScope.REFERENCES))
        assertTrue(telemetry.isEnabled(StandaloneTelemetryScope.RENAME))
        assertFalse(telemetry.isEnabled(StandaloneTelemetryScope.CALL_HIERARCHY))
        assertFalse(telemetry.isEnabled(StandaloneTelemetryScope.SYMBOL_RESOLVE))
        assertFalse(telemetry.isEnabled(StandaloneTelemetryScope.WORKSPACE_DISCOVERY))
    }

    @Test
    fun `fromEnvironment with KAST_DEBUG overrides KAST_OTEL_SCOPES to all`() {
        val telemetry = StandaloneTelemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(
                "KAST_DEBUG" to "true",
                "KAST_OTEL_SCOPES" to "rename",
            ),
        )

        StandaloneTelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isEnabled(scope), "KAST_DEBUG should force all scopes, but $scope was disabled")
        }
    }

    @Test
    fun `fromEnvironment with KAST_DEBUG overrides detail to verbose`() {
        val telemetry = StandaloneTelemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(
                "KAST_DEBUG" to "true",
                "KAST_OTEL_DETAIL" to "basic",
            ),
        )

        StandaloneTelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isVerbose(scope), "KAST_DEBUG should force verbose, but $scope was not verbose")
        }
    }

    // --- Detail parsing ---

    @Test
    fun `detail parse returns VERBOSE for verbose`() {
        assertEquals(StandaloneTelemetryDetail.VERBOSE, StandaloneTelemetryDetail.parse("verbose"))
    }

    @Test
    fun `detail parse returns BASIC for null or unknown`() {
        assertEquals(StandaloneTelemetryDetail.BASIC, StandaloneTelemetryDetail.parse(null))
        assertEquals(StandaloneTelemetryDetail.BASIC, StandaloneTelemetryDetail.parse("unknown"))
    }

    private fun mapEnvReader(vararg pairs: Pair<String, String>): (String) -> String? {
        val env = pairs.toMap()
        return { key -> env[key] }
    }
}
