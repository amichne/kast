package io.github.amichne.kast.standalone

import io.github.amichne.kast.standalone.telemetry.Telemetry
import io.github.amichne.kast.standalone.telemetry.TelemetryConfig
import io.github.amichne.kast.standalone.telemetry.TelemetryDetail
import io.github.amichne.kast.standalone.telemetry.TelemetryScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TelemetryConfigTest {
    @TempDir
    lateinit var workspaceRoot: Path

    // --- Scope parsing ---

    @Test
    fun `parse recognizes rename scope`() {
        assertEquals(TelemetryScope.RENAME, TelemetryScope.parse("rename"))
    }

    @Test
    fun `parse recognizes call-hierarchy variants`() {
        assertEquals(TelemetryScope.CALL_HIERARCHY, TelemetryScope.parse("call-hierarchy"))
        assertEquals(TelemetryScope.CALL_HIERARCHY, TelemetryScope.parse("call_hierarchy"))
        assertEquals(TelemetryScope.CALL_HIERARCHY, TelemetryScope.parse("callhierarchy"))
    }

    @Test
    fun `parse recognizes references scope variants`() {
        assertEquals(TelemetryScope.REFERENCES, TelemetryScope.parse("references"))
        assertEquals(TelemetryScope.REFERENCES, TelemetryScope.parse("find-references"))
        assertEquals(TelemetryScope.REFERENCES, TelemetryScope.parse("find_references"))
    }

    @Test
    fun `parse recognizes symbol-resolve scope variants`() {
        assertEquals(TelemetryScope.SYMBOL_RESOLVE, TelemetryScope.parse("symbol-resolve"))
        assertEquals(TelemetryScope.SYMBOL_RESOLVE, TelemetryScope.parse("symbol_resolve"))
        assertEquals(TelemetryScope.SYMBOL_RESOLVE, TelemetryScope.parse("symbolresolve"))
        assertEquals(TelemetryScope.SYMBOL_RESOLVE, TelemetryScope.parse("resolve"))
    }

    @Test
    fun `parse recognizes workspace-discovery scope variants`() {
        assertEquals(TelemetryScope.WORKSPACE_DISCOVERY, TelemetryScope.parse("workspace-discovery"))
        assertEquals(TelemetryScope.WORKSPACE_DISCOVERY, TelemetryScope.parse("workspace_discovery"))
        assertEquals(TelemetryScope.WORKSPACE_DISCOVERY, TelemetryScope.parse("workspacediscovery"))
        assertEquals(TelemetryScope.WORKSPACE_DISCOVERY, TelemetryScope.parse("discovery"))
    }

    @Test
    fun `parse recognizes session-lock scope variants`() {
        assertEquals(TelemetryScope.SESSION_LOCK, TelemetryScope.parse("session-lock"))
        assertEquals(TelemetryScope.SESSION_LOCK, TelemetryScope.parse("session_lock"))
        assertEquals(TelemetryScope.SESSION_LOCK, TelemetryScope.parse("sessionlock"))
        assertEquals(TelemetryScope.SESSION_LOCK, TelemetryScope.parse("lock"))
    }

    @Test
    fun `parse recognizes session-lifecycle scope variants`() {
        assertEquals(TelemetryScope.SESSION_LIFECYCLE, TelemetryScope.parse("session-lifecycle"))
        assertEquals(TelemetryScope.SESSION_LIFECYCLE, TelemetryScope.parse("session_lifecycle"))
        assertEquals(TelemetryScope.SESSION_LIFECYCLE, TelemetryScope.parse("sessionlifecycle"))
        assertEquals(TelemetryScope.SESSION_LIFECYCLE, TelemetryScope.parse("lifecycle"))
    }

    @Test
    fun `parse recognizes indexing scope variants`() {
        assertEquals(TelemetryScope.INDEXING, TelemetryScope.parse("indexing"))
        assertEquals(TelemetryScope.INDEXING, TelemetryScope.parse("index"))
    }

    @Test
    fun `parse returns null for unknown scope`() {
        assertNull(TelemetryScope.parse("unknown"))
        assertNull(TelemetryScope.parse(""))
    }

    @Test
    fun `parse is case-insensitive`() {
        assertEquals(TelemetryScope.REFERENCES, TelemetryScope.parse("REFERENCES"))
        assertEquals(TelemetryScope.REFERENCES, TelemetryScope.parse("References"))
    }

    // --- KAST_DEBUG support ---

    @Test
    fun `fromEnvironment with KAST_DEBUG enables all scopes and verbose detail`() {
        val telemetry = Telemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader("KAST_DEBUG" to "true"),
        )

        TelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isEnabled(scope), "Expected scope $scope to be enabled with KAST_DEBUG")
            assertTrue(telemetry.isVerbose(scope), "Expected scope $scope to be verbose with KAST_DEBUG")
        }
    }

    @Test
    fun `fromEnvironment with KAST_DEBUG=1 enables all scopes`() {
        val telemetry = Telemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader("KAST_DEBUG" to "1"),
        )

        TelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isEnabled(scope), "Expected scope $scope to be enabled with KAST_DEBUG=1")
        }
    }

    @Test
    fun `fromEnvironment without any env vars returns disabled telemetry`() {
        val telemetry = Telemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(),
        )

        TelemetryScope.entries.forEach { scope ->
            assertFalse(telemetry.isEnabled(scope), "Expected scope $scope to be disabled")
        }
    }

    @Test
    fun `fromEnvironment with KAST_OTEL_ENABLED and specific scopes`() {
        val telemetry = Telemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(
                "KAST_OTEL_ENABLED" to "true",
                "KAST_OTEL_SCOPES" to "references,rename",
            ),
        )

        assertTrue(telemetry.isEnabled(TelemetryScope.REFERENCES))
        assertTrue(telemetry.isEnabled(TelemetryScope.RENAME))
        assertFalse(telemetry.isEnabled(TelemetryScope.CALL_HIERARCHY))
        assertFalse(telemetry.isEnabled(TelemetryScope.SYMBOL_RESOLVE))
        assertFalse(telemetry.isEnabled(TelemetryScope.WORKSPACE_DISCOVERY))
    }

    @Test
    fun `fromEnvironment with KAST_DEBUG overrides KAST_OTEL_SCOPES to all`() {
        val telemetry = Telemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(
                "KAST_DEBUG" to "true",
                "KAST_OTEL_SCOPES" to "rename",
            ),
        )

        TelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isEnabled(scope), "KAST_DEBUG should force all scopes, but $scope was disabled")
        }
    }

    @Test
    fun `fromEnvironment with KAST_DEBUG overrides detail to verbose`() {
        val telemetry = Telemetry.fromEnvironment(
            workspaceRoot = workspaceRoot,
            envReader = mapEnvReader(
                "KAST_DEBUG" to "true",
                "KAST_OTEL_DETAIL" to "basic",
            ),
        )

        TelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isVerbose(scope), "KAST_DEBUG should force verbose, but $scope was not verbose")
        }
    }

    // --- Detail parsing ---

    @Test
    fun `detail parse returns VERBOSE for verbose`() {
        assertEquals(TelemetryDetail.VERBOSE, TelemetryDetail.parse("verbose"))
    }

    @Test
    fun `detail parse returns BASIC for null or unknown`() {
        assertEquals(TelemetryDetail.BASIC, TelemetryDetail.parse(null))
        assertEquals(TelemetryDetail.BASIC, TelemetryDetail.parse("unknown"))
    }

    private fun mapEnvReader(vararg pairs: Pair<String, String>): (String) -> String? {
        val env = pairs.toMap()
        return { key -> env[key] }
    }
}
