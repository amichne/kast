package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostSettingsConflictTest {
    private val heap = "KAST_HOST_MAX_HEAP_MIB"

    @Test
    fun `the conflict encodes the workspace and both sides of each public setting`() {
        val conflict: WorkspaceConfigurationConflict =
            WorkspaceConfigurationConflict.HostSettings(
                HostSettingsComparison.PREPARED,
                listOf(heap, "processEnvironment"),
                "/work/app",
                listOf(HostSettingDifference(heap, prepared = "2048", requested = null)),
            )
        assertEquals(
            """{"type":"HOST_SETTINGS","stage":"PREPARED","differences":["KAST_HOST_MAX_HEAP_MIB",""" +
                """"processEnvironment"],"workspace":"/work/app","values":[{"key":"KAST_HOST_MAX_HEAP_MIB",""" +
                """"prepared":"2048","requested":null}]}""",
            Json.encodeToString(WorkspaceConfigurationConflict.serializer(), conflict),
        )
    }

    @Test
    fun `a conflict without public values still encodes an empty list`() {
        val conflict: WorkspaceConfigurationConflict =
            WorkspaceConfigurationConflict.HostSettings(
                HostSettingsComparison.PREPARING,
                listOf("gradleImportEnvironmentIdentity"),
                "/work/app",
            )
        assertEquals(
            """{"type":"HOST_SETTINGS","stage":"PREPARING","differences":["gradleImportEnvironmentIdentity"],""" +
                """"workspace":"/work/app","values":[]}""",
            Json.encodeToString(WorkspaceConfigurationConflict.serializer(), conflict),
        )
    }

    @Test
    fun `recovery names the stop command for the exact workspace, quoted for a shell`() {
        fun recovery(stage: HostSettingsComparison, workspace: String) =
            WorkspaceConfigurationConflict.HostSettings(stage, listOf(heap), workspace).recovery()

        assertEquals(
            "The running Host keeps the prepared values. Run `kast stop --workspace /work/app` and retry to start " +
                "it with the requested values, or restore the prepared values.",
            recovery(HostSettingsComparison.PREPARED, "/work/app"),
        )
        assertEquals(
            "The Host is still starting with the prepared values. Retry with those values, or wait for " +
                "preparation, run `kast stop --workspace '/Users/a b/it'\\''s'`, and retry to start it with the " +
                "requested values.",
            recovery(HostSettingsComparison.PREPARING, "/Users/a b/it's"),
        )
    }
}
