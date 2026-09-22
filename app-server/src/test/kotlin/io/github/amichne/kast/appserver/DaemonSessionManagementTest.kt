package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.ControlFailure
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonSessionManagementTest {
    @Test
    fun `controller management rejects without admitting optional Codex host`(@TempDir temporary: Path) = runBlocking {
        val root = temporary.toRealPath()
        for (directory in listOf("bin", "lib", "share")) Files.createDirectory(root.resolve(directory))
        val kast = Files.writeString(root.resolve("bin/kast"), "fixture")
        Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
        val environment = mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private")
        val command =
            (BrokerServiceLaunchCommand.resolveCoordinator(kast, root, environment)
                    as BrokerServiceLaunchCommandResolution.Resolved)
                .command
        val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
        val options =
            (InstalledCoordinatorConfiguration.admit(
                    kast,
                    root,
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
            val action =
                (AppServerAction.Control.admit(
                        ControlOperation.CLAIM,
                        "thread-1",
                        "00000000-0000-0000-0000-000000000001",
                    ) as AppServerControlAdmission.Admitted)
                    .action
            val result = InstalledAppServerManager(kast, root, environment).execute(action, root)
            assertEquals(
                AppServerManagementResult.DaemonRejected(
                    DaemonManagementRejection.Control(ControlFailure.HOST_UNAVAILABLE)
                ),
                result,
            )
            assertPendingStatus(kast, root, environment)
            assertFalse(activities.any { it.stage == BrokerStartupStage.HOST_ADMISSION })
        } finally {
            running.close()
        }
    }

    private fun assertPendingStatus(kast: Path, root: Path, environment: Map<String, String>) {
        val status =
            InstalledAppServerManager(kast, root, environment).execute(AppServerAction.Status, root)
                as AppServerManagementResult.Completed
        val sessions = status.document.getValue("sessions").jsonObject
        assertEquals("observed", sessions.getValue("type").jsonPrimitive.content)
        assertEquals("pending", sessions.getValue("inspection").jsonObject.getValue("type").jsonPrimitive.content)
    }
}
