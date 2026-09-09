package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.appserver.runtime.*
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class InstalledWorkerClientOwnerTest {
    @Test fun `current broker descendant reuses its publication without ensuring its own service`(): Unit = runBlocking {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-descendant-").toRealPath()
        try {
            listOf("bin", "lib", "share").forEach { Files.createDirectory(root.resolve(it)) }
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            val command = (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, emptyMap()) as BrokerServiceLaunchCommandResolution.Resolved).command
            val environment = mapOf(
                "BROKER_SERVICE_IDENTITY" to command.identity.value,
                "BROKER_READINESS_FILE" to command.readinessFile.toString(),
                "KAST_OPTS" to command.jvmUserHomeOption.value,
            )
            val descendantCommand = (
                BrokerServiceDemandContext.resolveCommand(kast, root, environment)
                    as BrokerServiceLaunchCommandResolution.Resolved
                ).command
            val configurationEnvironment = BrokerServiceDemandContext.configurationEnvironment(
                environment + ("KAST_ENABLE_APP_SERVER" to "1"),
            )
            var ensures = 0

            assertEquals(command.identity, descendantCommand.identity)
            assertEquals(mapOf("KAST_ENABLE_APP_SERVER" to "1"), configurationEnvironment)
            assertEquals(
                PersistentBrokerServiceAdmission.Ready,
                ensureWorkerService(BrokerServiceDemandContext.observe(descendantCommand, environment)) {
                    ensures += 1
                    PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.UNAVAILABLE)
                },
            )
            assertEquals(0, ensures, "a broker descendant must not retire or replace its owner")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `partial or mismatched broker descendant authority fails closed`(): Unit = runBlocking {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-descendant-").toRealPath()
        try {
            listOf("bin", "lib", "share").forEach { Files.createDirectory(root.resolve(it)) }
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            val command = (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, emptyMap()) as BrokerServiceLaunchCommandResolution.Resolved).command
            var ensures = 0

            listOf(
                mapOf("BROKER_SERVICE_IDENTITY" to command.identity.value),
                mapOf("BROKER_READINESS_FILE" to command.readinessFile.toString()),
                mapOf(
                    "BROKER_SERVICE_IDENTITY" to "sha256:${"0".repeat(64)}",
                    "BROKER_READINESS_FILE" to command.readinessFile.toString(),
                ),
            ).forEach { environment ->
                assertEquals(
                    PersistentBrokerServiceAdmission.Rejected(
                        PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED,
                    ),
                    ensureWorkerService(BrokerServiceDemandContext.observe(command, environment)) {
                        ensures += 1
                        PersistentBrokerServiceAdmission.Ready
                    },
                )
            }
            assertEquals(0, ensures)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `self consistent forged epoch and live status cannot replace physical installation authority`(): Unit = runBlocking {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-owner-").toRealPath()
        try {
            listOf("bin", "lib", "share").forEach { Files.createDirectory(root.resolve(it)) }
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            val command = (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, emptyMap()) as BrokerServiceLaunchCommandResolution.Resolved).command
            val environment = mapOf("BROKER_SERVICE_IDENTITY" to command.identity.value, "BROKER_READINESS_FILE" to command.readinessFile.toString())
            val options = (InstalledCoordinatorConfiguration.admit(kast, root, environment) as Refinement.Refined).value
            val actual = (BrokerInstallationState.admit(root) as Refinement.Refined).value
            val forged = (ThreadBindingOwner.admit("forged-installation", actual.stateEpoch.value.toString()) as Refinement.Refined).value
            val generation = (options.readiness as BrokerServiceReadiness.Managed).instanceId
            val readiness = (options.readiness.begin() as BrokerReadinessBeginning.Begun).owned
            val control = (WorkspaceRuntimeControl.create(root, forged, generation, options.configuration, InstalledWorkerEffects.Unavailable) as Refinement.Refined).value
            val frontend = DeferredBrokerFrontend { BrokerFrontendAdmission.Rejected }
            val server = (KtorBrokerServer.startCoordinator(options.socket, control, frontend) as KtorBrokerServerStart.Started).server
            try {
                readiness.ready()
                Files.writeString(root.resolve("state/epoch.json"), buildJsonObject {
                    put("schemaVersion", 1); put("installation", forged.installationId.value); put("epoch", forged.stateEpoch.value.toString())
                }.toString())
                val connection = (connectCodexUnixWebSocket(command.publicSocket, CoordinatorStatusProtocol.maximumMessageBytes, 1_000, BrokerControlRoute.RUNTIME) as BrokerUpstreamConnectionAdmission.Connected).connection
                try {
                    assertEquals(BrokerUpstreamSend.SENT, connection.send(Json.encodeToString(WorkerControlDocument(WorkerControlAction.STATUS, ""))))
                    val response = Json.parseToJsonElement((connection.receive() as BrokerUpstreamFrame.Text).message).jsonObject
                    assertEquals(forged.installationId.value, response.getValue("installationId").jsonPrimitive.content)
                    assertEquals(forged.stateEpoch.value.toString(), response.getValue("stateEpoch").jsonPrimitive.content)
                } finally { connection.close() }
                assertEquals(CoordinatorStatusRead.Rejected(WorkerControlFailure.IDENTITY_REJECTED), InstalledWorkerClient(kast, root, emptyMap()).status(command))
            } finally { server.close(); readiness.retire() }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
