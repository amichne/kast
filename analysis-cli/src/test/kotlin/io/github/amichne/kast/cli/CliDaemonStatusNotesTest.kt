package io.github.amichne.kast.cli

import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.ServerInstanceDescriptor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliDaemonStatusNotesTest {
    @Test
    fun `workspace status note summarizes the selected ready daemon`() {
        val selected = candidate(pid = 41)
        val stale = candidate(
            pid = 17,
            pidAlive = false,
            reachable = false,
            ready = false,
            runtimeStatus = null,
            errorMessage = "Process 17 is not alive",
        )

        val note = daemonNoteFor(
            WorkspaceStatusResult(
                workspaceRoot = "/tmp/workspace",
                descriptorDirectory = "/tmp/workspace/.kast/instances",
                selected = selected,
                candidates = listOf(selected, stale),
            ),
        )

        assertTrue(checkNotNull(note).contains("ready"))
        assertTrue(note.contains("2 descriptors registered"))
    }

    @Test
    fun `workspace status note surfaces indexing messages`() {
        val indexing = candidate(
            pid = 52,
            ready = false,
            runtimeStatus = RuntimeStatusResponse(
                state = RuntimeState.INDEXING,
                healthy = true,
                active = true,
                indexing = true,
                backendName = "standalone",
                backendVersion = "0.1.0-SNAPSHOT",
                workspaceRoot = "/tmp/workspace",
                message = "indexing 42 files",
            ),
        )

        val note = daemonNoteFor(
            WorkspaceStatusResult(
                workspaceRoot = "/tmp/workspace",
                descriptorDirectory = "/tmp/workspace/.kast/instances",
                selected = indexing,
                candidates = listOf(indexing),
            ),
        )

        assertTrue(checkNotNull(note).contains("indexing"))
        assertTrue(note.contains("42 files"))
    }

    @Test
    fun `workspace ensure note includes the startup log path`() {
        val note = daemonNoteFor(
            WorkspaceEnsureResult(
                workspaceRoot = "/tmp/workspace",
                started = true,
                logFile = "/tmp/workspace/.kast/logs/standalone-daemon.log",
                selected = candidate(pid = 61),
            ),
        )

        assertTrue(checkNotNull(note).contains("started"))
        assertTrue(note.contains("standalone-daemon.log"))
    }

    private fun candidate(
        pid: Long,
        pidAlive: Boolean = true,
        reachable: Boolean = true,
        ready: Boolean = true,
        runtimeStatus: RuntimeStatusResponse? = RuntimeStatusResponse(
            state = RuntimeState.READY,
            healthy = true,
            active = true,
            indexing = false,
            backendName = "standalone",
            backendVersion = "0.1.0-SNAPSHOT",
            workspaceRoot = "/tmp/workspace",
            message = null,
        ),
        errorMessage: String? = null,
    ): RuntimeCandidateStatus {
        return RuntimeCandidateStatus(
            descriptorPath = "/tmp/workspace/.kast/instances/$pid.json",
            descriptor = ServerInstanceDescriptor(
                workspaceRoot = "/tmp/workspace",
                backendName = "standalone",
                backendVersion = "0.1.0-SNAPSHOT",
                socketPath = "/tmp/workspace/.kast/s",
                pid = pid,
            ),
            pidAlive = pidAlive,
            reachable = reachable,
            ready = ready,
            runtimeStatus = runtimeStatus,
            errorMessage = errorMessage,
        )
    }
}
