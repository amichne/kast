package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeResetPeerAcceptanceTest {
    @Test
    fun `native ownership capture waits for the accepted descriptor on the same connected peer`() {
        val owned = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kr-")
        val reader = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            val root = owned.resolve("kast")
            val daemon = Files.createDirectories(root.resolve("installation/bin")).resolve("kast-daemon")
            compileListener(owned, daemon)
            val socket =
                Files.createDirectories(owned.resolve("codex/app-server-control")).resolve("app-server-control.sock")
            val listener = ProcessBuilder(daemon.toString(), socket.toString()).start()
            child = listener
            val output = listener.inputReader()
            assertEquals("ready", reader.submit<String> { output.readLine() }.get(5, TimeUnit.SECONDS))
            val snapshot =
                (NativeLifecycleProcesses.observe(listener.pid()) as LifecycleProcessObservation.Present).snapshot
            val selected = (ResetScopedProcess.select(snapshot, root) as ResetProcessSelection.Selected).process
            val observations = mutableListOf<ResetProcessObservation>()
            val captured =
                captureAfterAcceptance(
                    selected,
                    listener,
                    {
                        reader.submit<String> { output.readLine() }.get(5, TimeUnit.SECONDS)
                    },
                    observations::add,
                )
            assertTrue(captured is ResetSharedSocketCapture.Captured, "accepted peer ownership was rejected")
            assertSignals(listener.pid(), observations)
            listener.destroyForcibly()
            assertTrue(listener.waitFor(5, TimeUnit.SECONDS))
            assertEquals(
                LifecycleProcessObservation.Absent,
                (captured as ResetSharedSocketCapture.Captured).retireAfterExit(
                    NativeLifecycleProcesses.observe(listener.pid())
                ),
            )
            assertFalse(Files.exists(socket))
        } finally {
            child?.let {
                if (it.isAlive) it.destroyForcibly()
                assertTrue(it.waitFor(5, TimeUnit.SECONDS))
            }
            reader.shutdownNow()
            Files.walkFileTree(owned, ResetDeletingVisitor)
        }
    }

    private fun assertSignals(pid: Long, observations: List<ResetProcessObservation>) {
        assertEquals(
            listOf(
                ResetProcessObservation(pid, ResetProcessStage.SOCKET_PEER, ResetProcessResult.PEER_PENDING),
                ResetProcessObservation(pid, ResetProcessStage.SOCKET_PEER, ResetProcessResult.IDENTITY_VERIFIED),
                ResetProcessObservation(pid, ResetProcessStage.SOCKET_CAPTURE, ResetProcessResult.IDENTITY_VERIFIED),
            ),
            observations,
        )
    }

    private fun compileListener(owned: Path, daemon: Path) {
        val source = owned.resolve("listener.c")
        Files.writeString(source, checkNotNull(javaClass.getResource("/lifecycle/deferred-listener.c")).readText())
        val compiler = ProcessBuilder("/usr/bin/cc", source.toString(), "-o", daemon.toString()).inheritIO().start()
        assertTrue(compiler.waitFor(15, TimeUnit.SECONDS), "fixture compiler timed out")
        assertEquals(0, compiler.exitValue(), "fixture compilation failed")
    }

    private fun captureAfterAcceptance(
        selected: ResetScopedProcess,
        listener: Process,
        line: () -> String,
        observe: (ResetProcessObservation) -> Unit,
    ): ResetSharedSocketCapture {
        var inspections = 0
        val captured =
            ResetOwnedSharedSocket.capture(
                selected,
                inspect = {
                    inspections++
                    assertTrue(inspections <= 4, "unexpected native descriptor inspection")
                    val result = runBounded(it)
                    assertTrue(result is ProcessObservation.Exited && result.code == 0, "fixture lsof failed")
                    if (inspections == 3) {
                        listener.outputStream.write('\n'.code)
                        listener.outputStream.flush()
                        assertEquals("accepted", line())
                    }
                    result
                },
                observe = observe,
            )
        assertEquals(4, inspections)
        return captured
    }
}
