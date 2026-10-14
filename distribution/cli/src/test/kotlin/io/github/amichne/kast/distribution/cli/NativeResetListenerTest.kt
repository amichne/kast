package io.github.amichne.kast.distribution.cli

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeResetListenerTest {
    @Test
    fun `native reset retires the scoped shared listener and closes its existing connection`() {
        val owned = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kr-")
        val reader = Executors.newSingleThreadExecutor()
        var child: Process? = null
        var foreign: Process? = null
        try {
            val executable = compileListener(owned)
            val root = owned.resolve("kast")
            val socket = Files.createDirectories(root.resolve("installation/state/run")).resolve("u.sock")
            val listener = startSharedListener(executable, socket)
            child = listener
            assertEquals("ready", reader.submit<String> { listener.inputReader().readLine() }.get(5, TimeUnit.SECONDS))
            val otherSocket = Files.createDirectories(owned.resolve("other/installation/state/run")).resolve("u.sock")
            val unrelated =
                ProcessBuilder(executable.toString(), "app-server", "--listen", "unix://$otherSocket").start()
            foreign = unrelated
            assertEquals("ready", reader.submit<String> { unrelated.inputReader().readLine() }.get(5, TimeUnit.SECONDS))
            SocketChannel.open(StandardProtocolFamily.UNIX).use { connection ->
                connection.connect(UnixDomainSocketAddress.of(socket))
                assertEquals(
                    "connected",
                    reader.submit<String> { listener.inputReader().readLine() }.get(5, TimeUnit.SECONDS),
                )
                val snapshot =
                    (NativeLifecycleProcesses.observe(listener.pid()) as LifecycleProcessObservation.Present).snapshot
                val selected = (ResetScopedProcess.select(snapshot, root) as ResetProcessSelection.Selected).process
                val observations = mutableListOf<ResetProcessObservation>()
                assertEquals(
                    LifecycleProcessObservation.Absent,
                    NativeForceDaemonRuntime.retireObserved(selected, Duration.ofSeconds(5), observations::add),
                )
                assertEquals(
                    -1,
                    reader.submit<Int> { connection.read(ByteBuffer.allocate(1)) }.get(5, TimeUnit.SECONDS),
                )
                assertTrue(unrelated.isAlive)
                assertEquals(ResetProcessResult.RETIRED, observations.last().outcome)
            }
        } finally {
            for (process in listOfNotNull(child, foreign)) {
                if (process.isAlive) process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            }
            reader.shutdownNow()
            Files.walkFileTree(owned, ResetDeletingVisitor)
        }
    }

    @Test
    fun `forced daemon retirement removes its proven shared socket before replacement`() =
        withScopedDaemon { selected, socket, _ ->
            assertEquals(
                LifecycleProcessObservation.Absent,
                NativeForceDaemonRuntime.retireObserved(selected, Duration.ofSeconds(5)) {},
            )
            assertFalse(Files.exists(socket), "retired shared socket blocks the replacement broker")
        }

    @Test
    fun `shared socket retirement preserves a live owner`() = withScopedDaemon { selected, socket, listener ->
        val captured = ResetOwnedSharedSocket.capture(selected) as ResetSharedSocketCapture.Captured
        val observations = mutableListOf<ResetProcessObservation>()
        assertEquals(
            LifecycleProcessObservation.Unavailable,
            captured.retireAfterExit(NativeLifecycleProcesses.observe(listener.pid()), observations::add),
        )
        assertEquals(
            listOf(
                ResetProcessObservation(
                    listener.pid(),
                    ResetProcessStage.SOCKET_RETIREMENT,
                    ResetProcessResult.OBSERVATION_REJECTED,
                )
            ),
            observations,
        )
        assertTrue(listener.isAlive)
        assertTrue(Files.exists(socket))
    }

    @Test
    fun `shared socket retirement preserves a changed file after owner exit`() =
        withScopedDaemon { selected, socket, listener ->
            val captured = ResetOwnedSharedSocket.capture(selected) as ResetSharedSocketCapture.Captured
            listener.destroyForcibly()
            assertTrue(listener.waitFor(5, TimeUnit.SECONDS))
            Files.delete(socket)
            Files.writeString(socket, "foreign replacement")
            assertEquals(
                LifecycleProcessObservation.Unavailable,
                captured.retireAfterExit(NativeLifecycleProcesses.observe(listener.pid())),
            )
            assertEquals("foreign replacement", Files.readString(socket))
        }

    @Test
    fun `shared socket capture rejects a foreign replacement before capture`() =
        withScopedDaemon { selected, socket, listener ->
            Files.delete(socket)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.bind(UnixDomainSocketAddress.of(socket))
            }
            assertEquals(
                ResetSharedSocketCapture.Rejected,
                ResetOwnedSharedSocket.capture(selected),
                "a descriptor pathname does not prove ownership of the replacement inode",
            )
            assertTrue(listener.isAlive)
            assertTrue(Files.exists(socket))
        }

    @Test
    fun `shared socket capture rejects an active foreign replacement despite an existing owned connection`() =
        withScopedDaemon { selected, socket, listener ->
            SocketChannel.open(StandardProtocolFamily.UNIX).use { existing ->
                existing.connect(UnixDomainSocketAddress.of(socket))
                Files.delete(socket)
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { replacement ->
                    replacement.bind(UnixDomainSocketAddress.of(socket))
                    assertEquals(ResetSharedSocketCapture.Rejected, ResetOwnedSharedSocket.capture(selected))
                    assertTrue(listener.isAlive)
                    assertTrue(replacement.isOpen)
                    assertTrue(Files.exists(socket))
                }
            }
        }

    @Test
    fun `shared socket peer proof rejects an incomplete descriptor before the next process`() =
        withScopedDaemon { selected, socket, _ ->
            val caller = ProcessHandle.current().pid()
            val documents =
                ArrayDeque(
                    listOf(
                        "p$caller\nf100\nd0xaaaa\nn->(none)\n",
                        "p$caller\nf100\nd0xaaaa\nn->(none)\nf101\nd0xbbbb\nn->0xcccc\n" +
                            "f102\nd0xffff\np${selected.snapshot.pid}\nf50\nd0xcccc\nn$socket\n",
                    )
                )
            assertEquals(
                ResetSocketPeerProof.REJECTED,
                ResetSocketPeerOwnership.prove(socket, selected.snapshot.pid, "/usr/sbin/lsof") {
                    assertTrue(documents.isNotEmpty(), "unexpected descriptor inspection")
                    ProcessObservation.Exited(0, documents.removeFirst())
                },
            )
            assertTrue(documents.isEmpty(), "unconsumed descriptor inspection")
        }

    private fun withScopedDaemon(action: (ResetScopedProcess, Path, Process) -> Unit) {
        val owned = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kr-")
        val reader = Executors.newSingleThreadExecutor()
        var child: Process? = null
        try {
            val root = owned.resolve("kast")
            val bin = Files.createDirectories(root.resolve("installation/bin"))
            val executable = compileListener(bin, "kast-daemon")
            val socket =
                Files.createDirectories(owned.resolve("codex/app-server-control")).resolve("app-server-control.sock")
            val listener = startSharedListener(executable, socket)
            child = listener
            assertEquals("ready", reader.submit<String> { listener.inputReader().readLine() }.get(5, TimeUnit.SECONDS))
            val snapshot =
                (NativeLifecycleProcesses.observe(listener.pid()) as LifecycleProcessObservation.Present).snapshot
            val selected = (ResetScopedProcess.select(snapshot, root) as ResetProcessSelection.Selected).process
            action(selected, socket, listener)
        } finally {
            child?.let {
                if (it.isAlive) it.destroyForcibly()
                assertTrue(it.waitFor(5, TimeUnit.SECONDS))
            }
            reader.shutdownNow()
            Files.walkFileTree(owned, ResetDeletingVisitor)
        }
    }

    private fun compileListener(owned: Path, name: String = "codex"): Path {
        val source = owned.resolve("listener.c")
        Files.writeString(source, checkNotNull(javaClass.getResource("/lifecycle/codex-listener.c")).readText())
        val executable = owned.resolve(name)
        val compiler = ProcessBuilder("/usr/bin/cc", source.toString(), "-o", executable.toString()).inheritIO().start()
        try {
            assertTrue(compiler.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, compiler.exitValue())
        } finally {
            if (compiler.isAlive) compiler.destroyForcibly()
        }
        return executable
    }

    private fun startSharedListener(executable: Path, socket: Path): Process =
        ProcessBuilder(
                executable.toString(),
                "-c",
                "features.code_mode_host=true",
                "app-server",
                "--analytics-default-enabled",
                "--listen",
                "unix://$socket",
            )
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
}
