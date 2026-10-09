package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AppServerStartupEnrollmentTest {
    @Test
    fun `enable from home starts service without enrolling invocation directory`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = Files.createDirectories(root.resolve("installation/bin")).parent
        val kast = Files.writeString(installation.resolve("bin/kast"), "fixture")
        Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
        val home = Files.createDirectory(root.resolve("home"))
        val calls = mutableListOf<BrokerServiceLaunchCommand>()
        val service = PersistentBrokerServiceHost { command ->
            check(calls.isEmpty()) { "unexpected additional start" }
            calls += command
            PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED)
        }
        val result =
            InstalledAppServerManager(
                    kast = kast,
                    userHome = home,
                    environment = mapOf(selectedBrokerJbr(root)),
                    serviceHost = service,
                )
                .execute(AppServerAction.Enable, home)

        assertEquals(
            AppServerManagementResult.Rejected(
                AppServerManagementFailure.SERVICE_UNAVAILABLE,
                PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED,
            ),
            result,
        )
        assertEquals(1, calls.size)
        assertEquals(kast, calls.single().kast)
        assertFalse(Files.exists(installation.resolve("config/workspaces.json")))
        assertFalse(Files.exists(home.resolve("Library/LaunchAgents")))
    }

    @Test
    fun `malformed registry blocks enable before starting service and preserves bytes`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = Files.createDirectories(root.resolve("installation/bin")).parent
        val kast = Files.writeString(installation.resolve("bin/kast"), "fixture")
        Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
        val home = Files.createDirectory(root.resolve("home"))
        val registry = Files.createDirectories(installation.resolve("config")).resolve("workspaces.json")
        Files.writeString(registry, "invalid registry")
        val result =
            InstalledAppServerManager(
                    kast = kast,
                    userHome = home,
                    environment = mapOf(selectedBrokerJbr(root)),
                    serviceHost =
                        PersistentBrokerServiceHost {
                            error("service start must not be observed")
                        },
                )
                .execute(AppServerAction.Enable, home)

        assertEquals(AppServerManagementResult.Rejected(AppServerManagementFailure.ENROLLMENT_REJECTED), result)
        assertEquals("invalid registry", Files.readString(registry))
    }
}
