package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerServiceLoginResumeTest {
    @Test
    fun `loaded exact login job clears a prior stop under the service lock`(@TempDir temporary: Path) {
        val command = command(temporary)
        val marker = command.stateDirectory.resolve("stopped")
        Files.writeString(marker, "stopped\n")
        var calls = 0
        val host =
            MacOsPersistentBrokerServiceHost(
                launchctl =
                    LaunchctlInvoker { arguments, _ ->
                        calls++
                        assertEquals("list", arguments[1])
                        LaunchctlInvocation.Completed
                    }
            )
        assertEquals(PersistentBrokerServiceAdmission.Ready, host.resumeLogin(command))
        assertFalse(Files.exists(marker))
        assertEquals(1, calls)

        Files.writeString(marker, "stopped\n")
        Files.writeString(ServiceLoginAgent.path(command), "foreign")
        assertEquals(
            PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED),
            host.resumeLogin(command),
        )
        assertTrue(Files.exists(marker))
        assertEquals(1, calls)
    }

    @Test
    fun `unloaded login job cannot clear a prior stop`(@TempDir temporary: Path) {
        val command = command(temporary)
        val marker = command.stateDirectory.resolve("stopped")
        Files.writeString(marker, "stopped\n")
        val host = MacOsPersistentBrokerServiceHost(launchctl = LaunchctlInvoker { _, _ -> LaunchctlInvocation.Absent })
        assertEquals(
            PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED),
            host.resumeLogin(command),
        )
        assertTrue(Files.exists(marker))
    }

    private fun command(temporary: Path): BrokerServiceLaunchCommand {
        val product = Files.createDirectory(temporary.resolve("product")).toRealPath()
        val bin = Files.createDirectory(product.resolve("bin"))
        val kast = executable(bin.resolve("kast"))
        executable(Files.createDirectories(product.resolve("share/kast/libexec")).resolve("kast-daemon"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val search = Files.createDirectory(temporary.resolve("empty-bin"))
        val command =
            (BrokerServiceLaunchCommand.resolveCoordinator(kast, home, mapOf("PATH" to search.toString()))
                    as BrokerServiceLaunchCommandResolution.Resolved)
                .command
        Files.createDirectories(command.stateDirectory)
        val receipt = command.stateDirectory.resolve("service.plist")
        Files.writeString(receipt, BrokerLaunchdServiceDocument.render(command))
        Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"))
        assertEquals(ServiceLoginAgentChange.READY, ServiceLoginAgent.publish(command))
        return command
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}
