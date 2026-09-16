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

    private fun cleanup(process: Process) {
        process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
}
