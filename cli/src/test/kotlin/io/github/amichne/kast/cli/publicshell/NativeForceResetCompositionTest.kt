package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.mcp.McpRequest
import io.github.amichne.kast.cli.mcp.McpResponse
import io.github.amichne.kast.cli.mcp.mcpWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Actual force uninstall under a disposable HOME with one live native client and no Host. */
@Tag("public-launcher")
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
class NativeForceResetCompositionTest {
    @Test
    fun `force uninstall retires a live native jq worker and preserves a foreign installation`(
        @TempDir evidence: Path
    ) {
        NativePublicFixture(Files.createDirectory(evidence.resolve("selected"))).use { selected ->
            NativePublicFixture(Files.createDirectory(evidence.resolve("foreign"))).use { foreign ->
                withJqWorker(selected, evidence.resolve("selected-jq.stderr")) { worker ->
                    withJqWorker(foreign, evidence.resolve("foreign-jq.stderr")) { unrelated ->
                        // Both genuine FILTER workers have an open stdin; EOF is deliberately withheld.
                        assertEquals(selected.payload.toString(), worker.toHandle().info().command().orElseThrow())
                        assertNotNull(worker.toHandle().info().startInstant().orElseThrow())
                        assertTrue(worker.isAlive)
                        assertTrue(unrelated.isAlive)
                        val reset =
                            selected.start(
                                "force",
                                listOf("-Duser.home=${selected.home}", "uninstall", "--force", "--json"),
                            )
                        selected.assertExit(reset, 0, "force")
                        assertTrue(worker.waitFor(5, TimeUnit.SECONDS), "Native jq worker survived force uninstall")
                        assertTrue(unrelated.isAlive, "Force uninstall signalled a foreign installation")
                        assertFalse(Files.exists(selected.installation))
                        assertTrue(Files.exists(foreign.installation))
                    }
                }
            }
        }
    }

    @Test
    fun `force uninstall retires a live native MCP client before it removes Control`(@TempDir evidence: Path) {
        NativePublicFixture(evidence).use { fixture ->
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
                val reset =
                    fixture.start("force", listOf("-Duser.home=${fixture.home}", "uninstall", "--force", "--json"))
                fixture.assertExit(reset, 0, "force")
                assertEquals(
                    "REMOVED",
                    Json.parseToJsonElement(Files.readString(evidence.resolve("force.stdout")))
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive
                        .content,
                )
                assertTrue(client.waitFor(5, TimeUnit.SECONDS), "Native client survived force uninstall")
                assertFalse(Files.exists(fixture.installation))
            } finally {
                if (client.isAlive) client.destroyForcibly()
                assertTrue(client.waitFor(5, TimeUnit.SECONDS))
                reader.shutdownNow()
                assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun withJqWorker(fixture: NativePublicFixture, stderr: Path, block: (Process) -> Unit) {
        val child =
            ProcessBuilder(fixture.payload.toString(), "-Xmx512m", "--internal-entrypoint=jq", "FILTER", "1024", "Lg==")
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
