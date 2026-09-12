package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppServerStatusTest {
    @Test
    fun `passive status observes coordinator and registry without attaching an upstream host`() =
        withPayload { root, kast ->
            runBlocking {
                val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
                val sink = BrokerStartupActivitySink {
                    activities += it
                    BrokerStartupActivityPublication.PUBLISHED
                }
                val command =
                    (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, emptyMap())
                            as BrokerServiceLaunchCommandResolution.Resolved)
                        .command
                val environment =
                    mapOf(
                        "BROKER_SERVICE_IDENTITY" to command.identity.value,
                        "BROKER_READINESS_FILE" to command.readinessFile.toString(),
                    )
                val options =
                    (InstalledCoordinatorConfiguration.admit(kast, root, environment, sink) as Refinement.Refined).value
                val workspace = Files.createDirectory(root.resolve("workspace"))
                assertTrue(
                    WorkspaceEnrollmentStore(root.resolve("config/workspaces.json")).enroll(workspace)
                        is Refinement.Refined
                )
                val running = (InstalledCoordinator.start(options) as InstalledCoordinatorStart.Started).coordinator
                try {
                    val result =
                        InstalledAppServerManager(kast, root, emptyMap()).execute(AppServerAction.Status, workspace)
                    assertTrue(result is AppServerManagementResult.Completed, result.toString())
                    val document = (result as AppServerManagementResult.Completed).document
                    assertEquals(
                        "ready",
                        document.getValue("coordinator").jsonObject.getValue("state").jsonPrimitive.content,
                    )
                    assertEquals(
                        "registered",
                        document.getValue("registry").jsonObject.getValue("state").jsonPrimitive.content,
                    )
                    assertEquals("1", document.getValue("registry").jsonObject.getValue("count").jsonPrimitive.content)
                    assertEquals("unobserved", document.getValue("protocol").jsonPrimitive.content)
                    assertEquals(
                        "pending",
                        document.getValue("host").jsonObject.getValue("attachment").jsonPrimitive.content,
                    )
                    val paths = document.getValue("paths").jsonObject
                    assertEquals(command.serviceLog.toString(), paths.getValue("serviceLog").jsonPrimitive.content)
                    assertEquals(
                        command.launchEnvironment.toString(),
                        paths.getValue("launchEnvironment").jsonPrimitive.content,
                    )
                    assertEquals(
                        root.resolve("config/environment").toString(),
                        paths.getValue("savedConfiguration").jsonPrimitive.content,
                    )
                    assertEquals(
                        root.resolve("config/workspaces.json").toString(),
                        paths.getValue("workspaceRegistry").jsonPrimitive.content,
                    )
                    assertFalse(activities.any { it.stage == BrokerStartupStage.HOST_ADMISSION })
                    assertFalse(Files.exists(root.resolve(".codex")))
                    assertFalse(Files.exists(root.resolve("state/run/u.sock")))
                } finally {
                    running.close()
                }
            }
        }

    @Test
    fun `passive status reports absent service and empty registry without creating state`() =
        withPayload { root, kast ->
            val result = InstalledAppServerManager(kast, root, emptyMap()).execute(AppServerAction.Status, root)
            assertTrue(result is AppServerManagementResult.Completed, result.toString())
            val document = (result as AppServerManagementResult.Completed).document
            assertEquals(
                "unavailable",
                document.getValue("coordinator").jsonObject.getValue("state").jsonPrimitive.content,
            )
            assertEquals("empty", document.getValue("registry").jsonObject.getValue("state").jsonPrimitive.content)
            assertFalse(Files.exists(root.resolve("state")))
            assertFalse(Files.exists(root.resolve("config")))
            assertFalse(Files.exists(root.resolve(".codex")))
        }

    @Test
    fun `configuration inspection compares desired revision with passive coordinator acknowledgement`() =
        withPayload { root, kast ->
            runBlocking {
                val saved =
                    Files.writeString(
                        Files.createDirectory(root.resolve("config")).resolve("environment"),
                        "KAST_WORKER_AGGREGATE_MIB=32768\n",
                    )
                val environment = mapOf("KAST_CONFIGURATION_FILE" to saved.toString())
                val command =
                    (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, environment)
                            as BrokerServiceLaunchCommandResolution.Resolved)
                        .command
                val launch =
                    environment +
                        mapOf(
                            "BROKER_SERVICE_IDENTITY" to command.identity.value,
                            "BROKER_READINESS_FILE" to command.readinessFile.toString(),
                        )
                val options = (InstalledCoordinatorConfiguration.admit(kast, root, launch) as Refinement.Refined).value
                val running = (InstalledCoordinator.start(options) as InstalledCoordinatorStart.Started).coordinator
                try {
                    val acknowledged =
                        InstalledConfigurationAppliedInspection.read(kast, root, environment, command.configuration)
                    assertTrue(acknowledged is AppliedConfigurationInspection.Acknowledged, acknowledged.toString())
                    Files.writeString(saved, "KAST_WORKER_AGGREGATE_MIB=40000\n")
                    val next =
                        (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, environment)
                                as BrokerServiceLaunchCommandResolution.Resolved)
                            .command
                    val pending =
                        InstalledConfigurationAppliedInspection.read(kast, root, environment, next.configuration)
                    assertTrue(pending is AppliedConfigurationInspection.Pending, pending.toString())
                    assertFalse(Files.exists(root.resolve("state/run/u.sock")))
                    assertFalse(Files.exists(root.resolve(".codex")))
                } finally {
                    running.close()
                }
            }
        }

    private fun withPayload(test: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-s-").toRealPath()
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
