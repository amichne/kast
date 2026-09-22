package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PriorInstallationReplacementTest {
    @Test
    fun `replacement stops owned processes and preserves a neighboring installation`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val prior = Files.createDirectory(root.resolve("prior"))
        val neighbor = Files.createDirectory(root.resolve("prior-neighbor"))
        val home = Files.createDirectory(root.resolve("home"))
        val processes =
            listOf(prior, neighbor).map { installation ->
                val script = Files.writeString(installation.resolve("worker.sh"), "echo ready\nsleep 60 &\nwait\n")
                ProcessBuilder("/bin/sh", script.toString()).redirectErrorStream(true).start()
            }
        try {
            processes.forEach { assertEquals("ready", it.inputStream.bufferedReader().readLine()) }
            assertEquals(InstallationChildOutcome.COMPLETED, replacePriorInstallation(prior, home))
            assertTrue(processes[0].waitFor(5, TimeUnit.SECONDS))
            assertTrue(processes[1].isAlive)
        } finally {
            processes.forEach(::cleanup)
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
    fun `replacement records exact IO failure without path data`(@TempDir temporary: Path) {
        val prior = Files.createDirectory(temporary.resolve("prior")).toRealPath()
        val observations = mutableListOf<InstallationChildObservation>()
        assertEquals(
            InstallationChildOutcome.IO_REJECTED,
            replacePriorInstallation(prior, temporary.resolve("missing-secret-home"), observations::add),
        )
        assertEquals(
            listOf(
                InstallationChildObservation(
                    stage = InstallationChildStage.PRIOR_REPLACEMENT,
                    outcome = InstallationChildOutcome.IO_REJECTED,
                )
            ),
            observations,
        )
        assertTrue(observations.none { "secret" in it.toJson() })
    }

    @Test
    fun `force reset removes derived sockets and aliases without following links`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = Files.createDirectory(root.resolve("installation-" + "x".repeat(100)))
        val home = Files.createDirectory(root.resolve("home"))
        val run = Files.createDirectories(installation.resolve("state/run"))
        val alias =
            io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases.transportPath(
                    run.resolve("c.sock")
                )
                .parent
        val upstream =
            io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories.transportPath(
                run.resolve("u.sock")
            )
        val retained = Files.writeString(run.resolve("keep"), "retained")
        val observations = mutableListOf<InstallationChildObservation>()
        Files.createSymbolicLink(alias, run)
        Files.createDirectory(upstream.parent)
        try {
            java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use {
                it.bind(java.net.UnixDomainSocketAddress.of(upstream))
            }
            assertEquals(InstallationChildOutcome.COMPLETED, resetInstallation(installation, home, observations::add))
            assertTrue(Files.notExists(alias, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.notExists(upstream.parent))
            assertEquals("retained", Files.readString(retained))
            assertEquals(
                InstallationChildObservation(
                    stage = InstallationChildStage.FORCE_RESET,
                    outcome = InstallationChildOutcome.COMPLETED,
                ),
                observations.last(),
            )

            Files.createDirectory(upstream.parent)
            val foreign = Files.writeString(upstream.parent.resolve("unexpected"), "retain")
            assertEquals(InstallationChildOutcome.IO_REJECTED, resetInstallation(installation, home, observations::add))
            assertEquals("retain", Files.readString(foreign))
            assertEquals(InstallationChildOutcome.IO_REJECTED, observations.last().outcome)
            Files.delete(foreign)
        } finally {
            Files.deleteIfExists(alias)
            Files.deleteIfExists(upstream)
            Files.deleteIfExists(upstream.parent)
        }
    }

    private fun cleanup(process: Process) {
        process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
}
