package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class InstalledSemanticCoordinatorTest {
    @Test
    fun `managed semantic readiness binds the process incarnation and exact socket`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                val readiness =
                    BrokerInstallationLayout.from(kast, root.resolve(".codex")).broker.resolve("service-readiness.json")
                val environment =
                    mapOf(
                        "BROKER_SERVICE_IDENTITY" to semanticServiceIdentity(root),
                        "BROKER_READINESS_FILE" to readiness.toString(),
                    )
                val options =
                    (InstalledCoordinatorConfiguration.admit(kast, root, environment) as Refinement.Refined).value
                val started =
                    InstalledCoordinator.start(options, semantic = { _, _ -> error("Unexpected request") })
                        as InstalledCoordinatorStart.Started
                try {
                    val state = Json.parseToJsonElement(Files.readString(readiness)).jsonObject
                    val peer = state.getValue("semanticPeer").jsonObject
                    val current = ProcessHandle.current()
                    assertEquals(current.pid().toString(), peer.getValue("pid").jsonPrimitive.content)
                    assertEquals(
                        current.info().startInstant().orElseThrow().toString(),
                        peer.getValue("startedAt").jsonPrimitive.content,
                    )
                    val socket =
                        Files.readAttributes(
                            options.socket.physicalPath,
                            java.nio.file.attribute.BasicFileAttributes::class.java,
                        )
                    assertEquals(socket.fileKey().toString(), peer.getValue("socketKey").jsonPrimitive.content)
                    assertPublishedSemanticOwner(root, kast, readiness, current)
                } finally {
                    started.coordinator.close()
                }
                assertFalse(Files.exists(readiness))
            }
        }

    private suspend fun assertPublishedSemanticOwner(
        root: Path,
        kast: Path,
        readiness: Path,
        process: ProcessHandle,
    ) {
        val authoritative = readServiceState(readiness) as BrokerServiceStateDocument.Ready
        val executable = Path.of(process.info().command().orElseThrow()).toRealPath()
        val live = InstalledSemanticCoordinator.observe(root, readiness, executable) as Refinement.Refined
        assertEquals(authoritative.serviceInstanceId, live.value.generation)
        assertEquals(
            Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED),
            InstalledSemanticCoordinator.observe(root, readiness, kast),
        )
        Files.setPosixFilePermissions(readiness, PosixFilePermissions.fromString("rw-r--r--"))
        try {
            assertEquals(
                Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED),
                InstalledSemanticCoordinator.observe(root, readiness, executable),
            )
        } finally {
            Files.setPosixFilePermissions(readiness, PosixFilePermissions.fromString("rw-------"))
        }
    }
}
