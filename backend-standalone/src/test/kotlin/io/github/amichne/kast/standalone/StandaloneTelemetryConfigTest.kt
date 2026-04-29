package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.client.KastConfig
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
import java.nio.file.Files
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
    fun `parse recognizes workspace-files scope variants`() {
        assertEquals(StandaloneTelemetryScope.WORKSPACE_FILES, StandaloneTelemetryScope.parse("workspace-files"))
        assertEquals(StandaloneTelemetryScope.WORKSPACE_FILES, StandaloneTelemetryScope.parse("workspace_files"))
        assertEquals(StandaloneTelemetryScope.WORKSPACE_FILES, StandaloneTelemetryScope.parse("workspacefiles"))
    }

    @Test
    fun `parse recognizes session-lock scope variants`() {
        assertEquals(StandaloneTelemetryScope.SESSION_LOCK, StandaloneTelemetryScope.parse("session-lock"))
        assertEquals(StandaloneTelemetryScope.SESSION_LOCK, StandaloneTelemetryScope.parse("session_lock"))
        assertEquals(StandaloneTelemetryScope.SESSION_LOCK, StandaloneTelemetryScope.parse("sessionlock"))
        assertEquals(StandaloneTelemetryScope.SESSION_LOCK, StandaloneTelemetryScope.parse("lock"))
    }

    @Test
    fun `parse recognizes session-lifecycle scope variants`() {
        assertEquals(StandaloneTelemetryScope.SESSION_LIFECYCLE, StandaloneTelemetryScope.parse("session-lifecycle"))
        assertEquals(StandaloneTelemetryScope.SESSION_LIFECYCLE, StandaloneTelemetryScope.parse("session_lifecycle"))
        assertEquals(StandaloneTelemetryScope.SESSION_LIFECYCLE, StandaloneTelemetryScope.parse("sessionlifecycle"))
        assertEquals(StandaloneTelemetryScope.SESSION_LIFECYCLE, StandaloneTelemetryScope.parse("lifecycle"))
    }

    @Test
    fun `parse recognizes indexing scope variants`() {
        assertEquals(StandaloneTelemetryScope.INDEXING, StandaloneTelemetryScope.parse("indexing"))
        assertEquals(StandaloneTelemetryScope.INDEXING, StandaloneTelemetryScope.parse("index"))
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

    // --- Config-backed telemetry ---

    @Test
    fun `fromConfig enables configured scopes and verbose detail`() {
        val telemetry = StandaloneTelemetry.fromConfig(
            workspaceRoot = workspaceRoot,
            config = KastConfig.defaults().copy(
                telemetry = KastConfig.defaults().telemetry.copy(
                    enabled = true,
                    scopes = "all",
                    detail = "verbose",
                ),
            ),
        )

        StandaloneTelemetryScope.entries.forEach { scope ->
            assertTrue(telemetry.isEnabled(scope), "Expected scope $scope to be enabled")
            assertTrue(telemetry.isVerbose(scope), "Expected scope $scope to be verbose")
        }
    }

    @Test
    fun `fromConfig with disabled telemetry returns disabled telemetry`() {
        val telemetry = StandaloneTelemetry.fromConfig(
            workspaceRoot = workspaceRoot,
            config = KastConfig.defaults().copy(
                telemetry = KastConfig.defaults().telemetry.copy(enabled = false),
            ),
        )

        StandaloneTelemetryScope.entries.forEach { scope ->
            assertFalse(telemetry.isEnabled(scope), "Expected scope $scope to be disabled")
        }
    }

    @Test
    fun `fromConfig enables specific scopes`() {
        val telemetry = StandaloneTelemetry.fromConfig(
            workspaceRoot = workspaceRoot,
            config = KastConfig.defaults().copy(
                telemetry = KastConfig.defaults().telemetry.copy(
                    enabled = true,
                    scopes = "references,rename",
                ),
            ),
        )

        assertTrue(telemetry.isEnabled(StandaloneTelemetryScope.REFERENCES))
        assertTrue(telemetry.isEnabled(StandaloneTelemetryScope.RENAME))
        assertFalse(telemetry.isEnabled(StandaloneTelemetryScope.CALL_HIERARCHY))
        assertFalse(telemetry.isEnabled(StandaloneTelemetryScope.SYMBOL_RESOLVE))
        assertFalse(telemetry.isEnabled(StandaloneTelemetryScope.WORKSPACE_DISCOVERY))
    }

    @Test
    fun `fromConfig writes default telemetry output under config directory`() {
        val configHome = workspaceRoot.resolve("config-home")
        val telemetry = StandaloneTelemetry.fromConfig(
            workspaceRoot = workspaceRoot,
            config = KastConfig.defaults().copy(
                telemetry = KastConfig.defaults().telemetry.copy(
                    enabled = true,
                    scopes = "workspace-files",
                ),
            ),
            configHome = { configHome },
        )

        telemetry.inSpan(StandaloneTelemetryScope.WORKSPACE_FILES, "kast.workspaceFiles") {}

        val telemetryFile = configHome.resolve("telemetry/standalone-spans.jsonl")
        assertTrue(Files.isRegularFile(telemetryFile), "Expected telemetry at $telemetryFile")
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

}
