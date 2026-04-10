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
                descriptorDirectory = "/tmp/.config/kast/daemons",
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
                descriptorDirectory = "/tmp/.config/kast/daemons",
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
                logFile = "/tmp/.config/kast/logs/a1b2c3d4e5f6/standalone-daemon.log",
                selected = candidate(pid = 61),
            ),
        )

        assertTrue(checkNotNull(note).contains("started"))
        assertTrue(note.contains("standalone-daemon.log"))
    }

    @Test
    fun `workspace ensure note prefers explicit override`() {
        val note = daemonNoteFor(
            WorkspaceEnsureResult(
                workspaceRoot = "/tmp/workspace",
                started = true,
                logFile = "/tmp/.config/kast/logs/a1b2c3d4e5f6/standalone-daemon.log",
                selected = candidate(pid = 71),
                note = "kast: started daemon for /tmp/workspace (state: INDEXING, enrichment in progress)",
            ),
        )

        assertTrue(checkNotNull(note).contains("INDEXING"))
        assertTrue(!note.contains("standalone-daemon.log"))
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
        ),
        errorMessage: String? = null,
    ): RuntimeCandidateStatus {
        return RuntimeCandidateStatus(
            descriptorPath = "/tmp/workspace:/standalone:$pid",
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
