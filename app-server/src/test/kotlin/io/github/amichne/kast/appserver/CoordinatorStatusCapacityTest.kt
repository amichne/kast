package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoordinatorStatusCapacityTest {
    @Test
    fun `passive transport admits the maximum valid worker status document`() = runBlocking {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kst-cap-").toRealPath()
        try {
            listOf("bin", "lib", "share").forEach { Files.createDirectory(root.resolve(it)) }
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            val command =
                (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, emptyMap())
                        as BrokerServiceLaunchCommandResolution.Resolved)
                    .command
            val owner = (BrokerInstallationState.admit(root) as Refinement.Refined).value
            val generation = UUID(0, 1).toString()
            Files.createDirectories(command.readinessFile.parent)
            Files.createDirectories(command.publicSocket.parent)
            Files.writeString(
                command.readinessFile,
                BROKER_SERVICE_STATE_JSON.encodeToString(
                    BrokerServiceStateDocument.serializer(),
                    BrokerServiceStateDocument.Ready(
                        BROKER_SERVICE_STATE_SCHEMA_VERSION,
                        command.identity.value,
                        generation,
                        VENDORED_BROKER_VERSION,
                    ),
                ),
            )
            val document =
                status(
                    owner.installationId.value,
                    owner.stateEpoch.value.toString(),
                    generation,
                    workerLaunchConfigurationIdentity(command.configuration),
                    256,
                )
            assertTrue(document.toString().toByteArray().size > 16_384)
            assertTrue(CoordinatorStatusSnapshot.admit(document) is Refinement.Refined)
            val engine =
                embeddedServer(
                    CIO,
                    configure = { unixConnector(command.publicSocket.toString()) },
                    module = {
                        install(WebSockets)
                        routing {
                            webSocket("/kast-runtime") {
                                val request = (incoming.receive() as Frame.Text).readText()
                                assertEquals(
                                    "STATUS",
                                    Json.parseToJsonElement(request)
                                        .jsonObject
                                        .getValue("action")
                                        .jsonPrimitive
                                        .content,
                                )
                                send(document.toString())
                            }
                        }
                    },
                )
            engine.startSuspend(wait = false)
            try {
                val observed = withTimeout(5_000) { InstalledWorkerClient(kast, root, emptyMap()).status(command) }
                assertTrue(observed is CoordinatorStatusRead.Observed, "valid bounded status was rejected: $observed")
                assertEquals(256, (observed as CoordinatorStatusRead.Observed).snapshot.workers.size)
                assertEquals(
                    BrokerSocketReachability.REACHABLE,
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        JdkBrokerSocketProbe.probeGeneration(command.publicSocket, UUID.fromString(generation))
                    },
                    "readiness probe rejected the same valid maximum-capacity STATUS reply",
                )
            } finally {
                engine.stopSuspend(0, 1_000)
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `one extra worker exceeds admitted status capacity`() {
        val document =
            status("sha256:" + "0".repeat(64), UUID(0, 1).toString(), UUID(0, 2).toString(), "0".repeat(64), 257)
        assertTrue(CoordinatorStatusSnapshot.admit(document) is Refinement.Rejected)
    }

    private fun status(
        installation: String,
        epoch: String,
        generation: String,
        configuration: String,
        count: Int,
    ): JsonObject = buildJsonObject {
        val reservation = Long.MAX_VALUE / count
        put("status", "READY")
        put("installationId", installation)
        put("stateEpoch", epoch)
        put("serviceGeneration", generation)
        put("configurationIdentity", configuration)
        put("reservedMiB", reservation * count)
        put("starting", 0)
        put("hostAttachment", "UNOBSERVED")
        putJsonArray("workers") {
            repeat(count) { index ->
                add(
                    buildJsonObject {
                        put("workspaceId", (index + 1).toString(16).padStart(64, '0'))
                        put("reservationId", UUID(0, index.toLong() + 1).toString())
                        put("phase", "QUARANTINED_RUNTIME")
                        put("reservedMiB", reservation)
                        put("requestedHeapMiB", Int.MAX_VALUE)
                        put("heapObservation", "UNOBSERVED")
                        put("configurationIdentity", configuration)
                    }
                )
            }
        }
    }
}
