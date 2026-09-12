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
    fun `passive transport admits the coordinator with no isolated workers`() = runBlocking {
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
            publishReadiness(command, generation)
            val document =
                status(
                    owner.installationId.value,
                    owner.stateEpoch.value.toString(),
                    generation,
                    coordinatorConfigurationIdentity(command.configuration),
                    0,
                )
            assertTrue(document.toString().toByteArray().size <= CoordinatorStatusProtocol.maximumMessageBytes)
            assertTrue(CoordinatorStatusSnapshot.admit(document) is Refinement.Refined)
            val engine = statusServer(command, document)
            engine.startSuspend(wait = false)
            try {
                val observed = withTimeout(5_000) { InstalledCoordinatorClient(kast).status(command) }
                assertTrue(observed is CoordinatorStatusRead.Observed, "valid bounded status was rejected: $observed")
                assertEquals(0, (observed as CoordinatorStatusRead.Observed).snapshot.workers.size)
                assertEquals(
                    BrokerSocketReachability.REACHABLE,
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        JdkBrokerSocketProbe.probeGeneration(command.publicSocket, UUID.fromString(generation))
                    },
                    "readiness probe rejected the same valid STATUS reply",
                )
            } finally {
                engine.stopSuspend(0, 1_000)
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun publishReadiness(command: BrokerServiceLaunchCommand, generation: String) {
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
    }

    private fun statusServer(command: BrokerServiceLaunchCommand, document: JsonObject) =
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
                            Json.parseToJsonElement(request).jsonObject.getValue("action").jsonPrimitive.content,
                        )
                        send(document.toString())
                    }
                }
            },
        )

    @Test
    fun `one extra worker exceeds admitted status capacity`() {
        val document =
            status("sha256:" + "0".repeat(64), UUID(0, 1).toString(), UUID(0, 2).toString(), "0".repeat(64), 1)
        assertTrue(CoordinatorStatusSnapshot.admit(document) is Refinement.Rejected)
    }

    private fun status(
        installation: String,
        epoch: String,
        generation: String,
        configuration: String,
        count: Int,
    ): JsonObject =
        Json.encodeToJsonElement(
                StatusFixture(
                    "READY",
                    installation,
                    epoch,
                    generation,
                    configuration,
                    count.toLong(),
                    0,
                    List(count) {
                        CoordinatorWorkerDocument(
                            workspaceId = "1".repeat(64),
                            reservationId = UUID(0, 1).toString(),
                            phase = "READY",
                            reservedMiB = 1,
                        )
                    },
                )
            )
            .jsonObject

    @kotlinx.serialization.Serializable
    private data class StatusFixture(
        val status: String,
        val installationId: String,
        val stateEpoch: String,
        val serviceGeneration: String,
        val configurationIdentity: String,
        val reservedMiB: Long,
        val starting: Int,
        val workers: List<CoordinatorWorkerDocument>,
    )
}
