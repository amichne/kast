package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.NativeCoordinatorProbe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Actual native startup, management status, readiness, and shutdown in an owned private installation. */
@Tag("public-launcher")
class NativeCoordinatorCompositionTest {
    @Test
    fun `native daemon serves management before optional host admission and removes its state on termination`(
        @TempDir evidence: Path
    ) {
        NativePublicFixture(evidence).use { fixture ->
            val child = startCoordinator(fixture, evidence)
            try {
                awaitReady(child)
                assertNativeStatus(child.socket)
                child.process.destroy()
                assertTrue(child.process.waitFor(20, TimeUnit.SECONDS), Files.readString(child.error).take(8192))
                assertEquals(143, child.process.exitValue())
                assertFalse(Files.exists(child.readiness))
                assertFalse(Files.exists(child.socket))
            } finally {
                if (child.process.isAlive) {
                    child.process.destroyForcibly()
                    assertTrue(child.process.waitFor(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun startCoordinator(fixture: NativePublicFixture, evidence: Path): CoordinatorChild {
        val kast = Files.writeString(fixture.installation.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
        assertTrue(kast.toFile().setExecutable(true))
        val codex = Files.createDirectory(fixture.home.resolve("codex"))
        val profile =
            MessageDigest.getInstance("SHA-256")
                .digest(codex.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
        val readiness = fixture.installation.resolve("state/broker/$profile/service-readiness.json")
        Files.createDirectories(readiness.parent)
        val identity = "sha256:" + "a".repeat(64)
        val error = evidence.resolve("daemon.stderr")
        val process =
            ProcessBuilder(fixture.payload.toString(), "-Duser.home=${fixture.home}", "--internal-entrypoint=daemon")
                .directory(fixture.home.toFile())
                .redirectOutput(evidence.resolve("daemon.stdout").toFile())
                .redirectError(error.toFile())
                .apply {
                    environment().clear()
                    environment()
                        .putAll(
                            mapOf(
                                "HOME" to fixture.home.toString(),
                                "PATH" to "/usr/bin:/bin",
                                "CODEX_HOME" to codex.toString(),
                                "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private",
                                "BROKER_SERVICE_IDENTITY" to identity,
                                "BROKER_READINESS_FILE" to readiness.toString(),
                            )
                        )
                }
                .start()
        return CoordinatorChild(process, readiness, fixture.installation.resolve("state/run/c.sock"), identity, error)
    }

    private fun awaitReady(child: CoordinatorChild) {
        child.readiness.fileSystem.newWatchService().use { watch ->
            child.readiness.parent.register(watch, ENTRY_CREATE, ENTRY_MODIFY)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (
                !Files.exists(child.readiness) ||
                    !NativeCoordinatorProbe.ready(Files.readString(child.readiness), child.identity)
            ) {
                assertTrue(child.process.isAlive, Files.readString(child.error).take(8192))
                val remaining = deadline - System.nanoTime()
                assertTrue(remaining > 0, "Native readiness did not settle")
                val event =
                    checkNotNull(watch.poll(remaining, TimeUnit.NANOSECONDS)) {
                        "Native readiness event is absent: ${Files.readString(child.error).take(8192)}"
                    }
                event.pollEvents()
                assertTrue(event.reset())
            }
        }
    }

    private fun assertNativeStatus(socket: Path) = runBlocking {
        HttpClient(CIO) { install(WebSockets) }
            .use { client ->
                withTimeout(10_000) { readNativeStatus(socket, client) }
            }
    }

    private suspend fun readNativeStatus(socket: Path, client: HttpClient) {
        client.webSocket({
            url("ws://localhost${NativeCoordinatorProbe.route}")
            unixSocket(socket.toString())
        }) {
            send(NativeCoordinatorProbe.statusRequest())
            val response = assertInstanceOf(Frame.Text::class.java, incoming.receive())
            NativeCoordinatorProbe.assertReadyWithPendingHost(response.readText())
        }
    }

    private data class CoordinatorChild(
        val process: Process,
        val readiness: Path,
        val socket: Path,
        val identity: String,
        val error: Path,
    )
}
