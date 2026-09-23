package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
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
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DaemonQueryRouteTest {
    @Test
    fun `query RPC rejects stale identity before opening a workspace or optional Codex host`() =
        withPayload { root, kast ->
            runBlocking {
                val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
                val options =
                    (InstalledCoordinatorConfiguration.admit(
                            kast,
                            root,
                            emptyMap(),
                            BrokerStartupActivitySink {
                                activities += it
                                BrokerStartupActivityPublication.PUBLISHED
                            },
                        ) as Refinement.Refined)
                        .value
                val running = (InstalledCoordinator.start(options) as InstalledCoordinatorStart.Started).coordinator
                val client = HttpClient(CIO) { install(WebSockets) }
                try {
                    val stale =
                        DaemonQueryRequest(
                            DaemonManagementTarget("stale", "stale", "stale", "stale"),
                            "\u0000",
                            JsonNull,
                        )
                    var result: DaemonQueryResponse? = null
                    withTimeout(5_000) {
                        client.webSocket({
                            url("ws://localhost${DaemonQueryProtocol.route}")
                            unixSocket(options.socket.path.toString())
                        }) {
                            send(DaemonQueryProtocol.json.encodeToString(DaemonQueryRequest.serializer(), stale))
                            result =
                                DaemonQueryProtocol.json.decodeFromString<DaemonQueryResponse>(
                                    (incoming.receive() as Frame.Text).readText()
                                )
                        }
                    }
                    assertEquals(
                        DaemonQueryResponse.Rejected(
                            DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.IDENTITY_REJECTED)
                        ),
                        result,
                    )
                    assertFalse(activities.any { it.stage == BrokerStartupStage.HOST_ADMISSION })
                } finally {
                    client.close()
                    running.close()
                }
            }
        }

    private fun withPayload(test: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-q-").toRealPath()
        try {
            for (directory in listOf("bin", "lib", "share")) Files.createDirectory(root.resolve(directory))
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            test(root, kast)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
