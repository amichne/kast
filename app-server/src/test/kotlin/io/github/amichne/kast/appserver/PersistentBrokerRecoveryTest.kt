package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PersistentBrokerRecoveryTest {
    @Test
    fun `destructive recovery removes only the exact installation state tree`(@TempDir temporary: Path) {
        val command = command(temporary)
        val state = command.kast.parent.parent.resolve("state")
        val runtimePayloads = Files.createDirectories(command.kast.parent.parent.resolve("runtime-payloads"))
        Files.writeString(runtimePayloads.resolve("poisoned-runtime"), "state")
        Files.createDirectories(command.stateDirectory)
        Files.writeString(command.stateDirectory.resolve("poisoned"), "state")
        val outside = Files.createDirectory(temporary.resolve("outside"))
        val canary = Files.writeString(outside.resolve("canary"), "preserved")
        Files.createSymbolicLink(state.resolve("outside-link"), outside)
        var present = true
        val operations = mutableListOf<String>()
        val host =
            MacOsPersistentBrokerServiceHost(
                launchctl =
                    LaunchctlInvoker { arguments, _ ->
                        operations += arguments[1]
                        when (arguments[1]) {
                            "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                            "bootout" -> {
                                present = false
                                LaunchctlInvocation.Completed
                            }
                            else -> error("unexpected launchctl operation: ${arguments[1]}")
                        }
                    },
                socketProbe = BrokerSocketProbe { BrokerSocketReachability.UNREACHABLE },
                sleeper = BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
                retirementTimeoutNanos = TimeUnit.SECONDS.toNanos(1),
            )

        assertEquals(PersistentBrokerServiceAdmission.Ready, host.destructiveReset(command))
        assertFalse(Files.exists(state, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(runtimePayloads, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("preserved", Files.readString(canary))
        assertEquals(1, operations.count { it == "bootout" })
    }

    @Test
    fun `destructive recovery preserves state when desktop ownership is malformed`(@TempDir temporary: Path) {
        val command = command(temporary)
        Files.createDirectories(command.stateDirectory)
        Files.setPosixFilePermissions(
            command.stateDirectory,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        )
        var reads = 0
        val environment =
            object : DesktopDaemonEnvironment {
                override fun read(): DesktopDaemonEnvironmentRead {
                    assertEquals(0, reads++)
                    return DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.ENABLED)
                }

                override fun enable(): DesktopDiscoveryOutcome = throw AssertionError("Unexpected environment mutation")

                override fun remove(): DesktopDiscoveryOutcome = throw AssertionError("Unexpected environment mutation")
            }
        val discovery = DesktopDaemonDiscovery(environment)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(DesktopDiscoveryTarget.from(command)))
        val record = command.stateDirectory.resolve("desktop-discovery/ownership.json")
        Files.writeString(record, "not-json")
        var observations = 0
        val host =
            MacOsPersistentBrokerServiceHost(
                launchctl =
                    LaunchctlInvoker { arguments, _ ->
                        assertEquals("list", arguments[1])
                        assertTrue(observations++ < 3, "Unexpected extra launchd observation")
                        LaunchctlInvocation.Absent
                    },
                socketProbe = BrokerSocketProbe { throw AssertionError("Unexpected socket observation") },
                sleeper = BrokerServiceSleeper { throw AssertionError("Unexpected wait") },
                desktopDiscovery = discovery,
            )
        assertEquals(
            PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.DESKTOP_DISCOVERY_REJECTED),
            host.destructiveReset(command),
        )
        assertEquals(3, observations)
        assertEquals(1, reads)
        assertEquals("not-json", Files.readString(record))
        assertTrue(Files.exists(command.stateDirectory))
    }

    private fun command(temporary: Path): BrokerServiceLaunchCommand {
        val product = Files.createDirectories(temporary.resolve("product")).toRealPath()
        val kast = executable(Files.createDirectories(product.resolve("bin")).resolve("kast"))
        executable(Files.createDirectories(product.resolve("share/kast/libexec")).resolve("kast-daemon"))
        val tools = Files.createDirectories(temporary.resolve("tools")).toRealPath()
        executable(tools.resolve("codex"))
        val home = Files.createDirectories(temporary.resolve("home")).toRealPath()
        val environment =
            mapOf("PATH" to tools.toString(), "KAST_RUNTIME_DIRECTORY" to home.resolve("runtime").toString())
        return when (val resolution = BrokerServiceLaunchCommand.resolve(kast, home, environment)) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command
            is BrokerServiceLaunchCommandResolution.Rejected -> throw AssertionError(resolution.failure)
        }
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}
