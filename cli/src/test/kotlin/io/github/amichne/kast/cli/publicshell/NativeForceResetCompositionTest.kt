package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.mcp.McpRequest
import io.github.amichne.kast.cli.mcp.McpResponse
import io.github.amichne.kast.cli.mcp.mcpWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Actual `kast reset --yes` under a disposable HOME with one live native client and no Host. The fixture's runtime is a
 * copy in the workspace store, so the reset selects its processes by their executable, as on a real installation.
 */
@Tag("public-launcher")
class NativeForceResetCompositionTest {
    @Test
    fun `a reset retires a live native jq worker and preserves a foreign installation`(@TempDir evidence: Path) {
        NativePublicFixture(Files.createDirectory(evidence.resolve("selected")), ownedRuntime = true).use { selected ->
            NativePublicFixture(Files.createDirectory(evidence.resolve("foreign")), ownedRuntime = true).use { foreign ->
                withJqWorker(selected, evidence.resolve("selected-jq.stderr")) { worker ->
                    withJqWorker(foreign, evidence.resolve("foreign-jq.stderr")) { unrelated ->
                        // Both genuine warm workers have an open stdin; EOF is deliberately withheld.
                        // The Control executable replaces itself with the runtime's java for the jq entry.
                        assertEquals(selected.javaExecutable.toString(), awaitExec(worker, selected.javaExecutable))
                        assertNotNull(worker.toHandle().info().startInstant().orElseThrow())
                        assertTrue(worker.isAlive)
                        assertTrue(unrelated.isAlive)
                        val reset = selected.start("reset", listOf("reset", "--yes"))
                        selected.assertExit(reset, 0, "reset")
                        assertTrue(worker.waitFor(5, TimeUnit.SECONDS), "Native jq worker survived the reset")
                        assertTrue(unrelated.isAlive, "The reset signalled a foreign installation")
                        assertFalse(Files.exists(selected.installation))
                        assertTrue(Files.exists(foreign.installation))
                    }
                }
            }
        }
    }

    @Test
    fun `a reset retires a live native MCP client before it removes Control`(@TempDir evidence: Path) {
        NativePublicFixture(evidence, ownedRuntime = true).use { fixture ->
            val client = startMcp(fixture, evidence)
            val reader = Executors.newSingleThreadExecutor()
            try {
                val initialized =
                    reader.submit<McpResponse> {
                        mcpWire.decodeFromString(checkNotNull(client.inputStream.bufferedReader().readLine()))
                    }
                client.outputStream.write(
                    (mcpWire.encodeToString(McpRequest(id = JsonPrimitive(1), method = "initialize")) + "\n")
                        .toByteArray()
                )
                client.outputStream.flush()
                assertNotNull(initialized.get(20, TimeUnit.SECONDS).result)
                assertTrue(client.isAlive)
                val reset = fixture.start("reset", listOf("reset", "--yes"))
                fixture.assertExit(reset, 0, "reset")
                // The reset lists what it stopped, then reports that nothing of Kast remains for this user.
                val report = Files.readString(evidence.resolve("reset.stdout"))
                assertTrue(report.contains("Stop:\n"), report)
                assertTrue(report.contains("Kast is not installed for this user."), report)
                assertTrue(client.waitFor(5, TimeUnit.SECONDS), "Native client survived the reset")
                assertFalse(Files.exists(fixture.installation))
                assertFalse(Files.exists(fixture.command))
            } finally {
                if (client.isAlive) client.destroyForcibly()
                assertTrue(client.waitFor(5, TimeUnit.SECONDS))
                reader.shutdownNow()
                assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    /** The process command once the Control executable has replaced itself, observed within a bounded window. */
    private fun awaitExec(process: Process, expected: Path): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            // The fixture's Java home is a link, as ownership proofs resolve it.
            val command = process.toHandle().info().command().map { Path.of(it).toRealPath().toString() }.orElse("")
            if (command == expected.toString() || System.nanoTime() > deadline || !process.isAlive) return command
            Thread.onSpinWait()
        }
    }

    private fun withJqWorker(fixture: NativePublicFixture, stderr: Path, block: (Process) -> Unit) {
        val child =
            ProcessBuilder(fixture.payload.toString(), "-Xmx512m", "--internal-entrypoint=jq")
                .directory(fixture.home.toFile())
                .redirectError(stderr.toFile())
                .apply { environment().clear() }
                .start()
        try {
            block(child)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
        }
    }

    private fun startMcp(fixture: NativePublicFixture, evidence: Path): Process =
        ProcessBuilder(
                fixture.payload.toString(),
                "-Duser.home=${fixture.home}",
                "-Xmx256m",
                "--internal-entrypoint=mcp",
                "--catalog=COMPLETE",
            )
            .directory(fixture.home.toFile())
            .redirectError(evidence.resolve("mcp.stderr").toFile())
            .apply {
                environment().clear()
                environment()["HOME"] = fixture.home.toString()
                environment()["PATH"] = "/usr/bin:/bin"
            }
            .start()
}
