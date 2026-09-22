package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceRegistrationCommandTest {
    @Test
    fun `registration preserves desired roots through daemon without admitting Codex`(@TempDir temporary: Path) =
        runBlocking {
            val installation = Files.createDirectories(temporary.resolve("installation/bin")).parent.toRealPath()
            val kast = installedExecutable(installation)
            val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
            val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
            val environment = mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private")
            val command =
                (BrokerServiceLaunchCommand.resolveCoordinator(kast, home, environment)
                        as BrokerServiceLaunchCommandResolution.Resolved)
                    .command
            val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
            val options =
                (InstalledCoordinatorConfiguration.admit(
                        kast,
                        home,
                        environment +
                            mapOf(
                                "BROKER_SERVICE_IDENTITY" to command.identity.value,
                                "BROKER_READINESS_FILE" to command.readinessFile.toString(),
                            ),
                        BrokerStartupActivitySink {
                            activities += it
                            BrokerStartupActivityPublication.PUBLISHED
                        },
                    ) as Refinement.Refined)
                    .value
            val running = (InstalledCoordinator.start(options) as InstalledCoordinatorStart.Started).coordinator
            try {
                val manager = InstalledAppServerManager(kast, home, environment)
                val result = manager.execute(AppServerAction.Register, workspace)
                assertTrue(result is AppServerManagementResult.Completed, result.toString())
                val document = (result as AppServerManagementResult.Completed).document
                assertEquals(workspace.toString(), document["root"]?.jsonPrimitive?.content)
                assertEquals("app-server.register", document["operation"]?.jsonPrimitive?.content)
                assertTrue(document["workspaceId"]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{64}")) == true)
                assertEquals("1", document["revision"]?.jsonPrimitive?.content)
                assertTrue(Files.isRegularFile(installation.resolve("config/workspaces.json")))
                assertFalse(activities.any { it.stage == BrokerStartupStage.HOST_ADMISSION })
                assertFalse(Files.exists(home.resolve("Library")))
                assertEquals(
                    document,
                    (manager.execute(AppServerAction.Register, workspace) as AppServerManagementResult.Completed)
                        .document,
                )
            } finally {
                running.close()
            }
        }

    @Test
    fun `registration rejects relative root before service observation`(@TempDir temporary: Path) {
        val installation = Files.createDirectories(temporary.resolve("installation/bin")).parent.toRealPath()
        val kast = installedExecutable(installation)
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val result = InstalledAppServerManager(kast, home, emptyMap()).execute(AppServerAction.Register, Path.of("."))
        assertEquals(
            AppServerManagementResult.DaemonRejected(
                DaemonManagementRejection.Enrollment(EnrollmentFailure.PATH_REJECTED)
            ),
            result,
        )
        assertFalse(Files.exists(installation.resolve("config/workspaces.json")))
    }

    private fun installedExecutable(installation: Path): Path {
        Files.createDirectories(installation.resolve("lib"))
        Files.createDirectories(installation.resolve("share"))
        val kast = Files.writeString(installation.resolve("bin/kast"), "fixture")
        Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
        return kast
    }

    @Test
    fun `offline registration rejects without changing registry`(@TempDir temporary: Path) {
        val installation = Files.createDirectories(temporary.resolve("installation/bin")).parent.toRealPath()
        val kast = Files.writeString(installation.resolve("bin/kast"), "fixture")
        Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val result = InstalledAppServerManager(kast, home, emptyMap()).execute(AppServerAction.Register, workspace)
        assertTrue(result is AppServerManagementResult.DaemonRejected, result.toString())
        assertEquals(
            DaemonManagementRejection.Coordinator(WorkerControlFailure.IDENTITY_REJECTED),
            (result as AppServerManagementResult.DaemonRejected).reason,
        )
        assertFalse(Files.exists(installation.resolve("config/workspaces.json")))
        assertFalse(Files.exists(home.resolve("Library")))
    }
}
