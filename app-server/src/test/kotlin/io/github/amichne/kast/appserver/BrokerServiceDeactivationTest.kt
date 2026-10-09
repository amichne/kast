package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerServiceDeactivationTest {
    @Test
    fun `disable retains recovered identity through service discovery and login cleanup`(@TempDir temporary: Path) {
        val script = Script("home", "read:ABSENT", "enable", "read:ENABLED", "read:ENABLED", "remove", "read:ABSENT")
        val fixture = fixture(temporary, script)
        assertEquals(ServiceLoginAgentObservation.REJECTED, ServiceLoginAgent.observe(fixture.requested))
        val host = stoppingHost(fixture.initial)
        assertEquals(
            Refinement.Refined(Unit),
            BrokerServiceDeactivation(host, fixture.discovery)
                .execute(fixture.requested, BrokerServiceDeactivationMode.DISABLE),
        )
        assertFalse(Files.exists(fixture.record))
        assertFalse(Files.exists(ServiceLoginAgent.path(fixture.initial)))
        assertFalse(Files.exists(fixture.initial.readinessFile))
        assertTrue(Files.exists(fixture.initial.stateDirectory.resolve("stopped")))
        script.exhausted()
    }

    @Test
    fun `stop of recovered identity retains discovery and login ownership`(@TempDir temporary: Path) {
        val script = Script("home", "read:ABSENT", "enable", "read:ENABLED")
        val fixture = fixture(temporary, script)
        assertEquals(
            Refinement.Refined(Unit),
            BrokerServiceDeactivation(stoppingHost(fixture.initial), fixture.discovery)
                .execute(fixture.requested, BrokerServiceDeactivationMode.STOP),
        )
        assertTrue(Files.exists(fixture.record))
        assertEquals(ServiceLoginAgentObservation.SERVICE, ServiceLoginAgent.observe(fixture.initial))
        script.exhausted()
    }

    @Test
    fun `foreign login agent rejects disable before stop or discovery effects`(@TempDir temporary: Path) {
        val script = Script("home", "read:ABSENT", "enable", "read:ENABLED")
        val fixture = fixture(temporary, script)
        val agent = ServiceLoginAgent.path(fixture.initial)
        Files.writeString(agent, "deliberately foreign login agent")
        assertEquals(
            Refinement.Rejected(BrokerServiceDeactivationFailure.LoginOwnership),
            BrokerServiceDeactivation(unexpectedHost(), fixture.discovery)
                .execute(fixture.requested, BrokerServiceDeactivationMode.DISABLE),
        )
        assertEquals("deliberately foreign login agent", Files.readString(agent))
        assertTrue(Files.exists(fixture.initial.readinessFile))
        assertTrue(Files.exists(fixture.record))
        assertFalse(Files.exists(fixture.initial.stateDirectory.resolve("stopped")))
        script.exhausted()
    }

    @Test
    fun `failed recovered stop preserves login discovery and exact rejection`(@TempDir temporary: Path) {
        val script = Script("home", "read:ABSENT", "enable", "read:ENABLED")
        val fixture = fixture(temporary, script)
        Files.writeString(fixture.initial.readinessFile, "deliberately malformed readiness")
        assertEquals(
            Refinement.Rejected(
                BrokerServiceDeactivationFailure.Service(PersistentBrokerServiceFailure.READINESS_REJECTED)
            ),
            BrokerServiceDeactivation(unexpectedHost(), fixture.discovery)
                .execute(fixture.requested, BrokerServiceDeactivationMode.DISABLE),
        )
        assertEquals("deliberately malformed readiness", Files.readString(fixture.initial.readinessFile))
        assertEquals(ServiceLoginAgentObservation.SERVICE, ServiceLoginAgent.observe(fixture.initial))
        assertTrue(Files.exists(fixture.record))
        script.exhausted()
    }

    private fun stoppingHost(command: BrokerServiceLaunchCommand): MacOsPersistentBrokerServiceHost {
        val operations = ArrayDeque(listOf("bootout", "list"))
        var probes = 0
        return MacOsPersistentBrokerServiceHost(
            launchctl =
                LaunchctlInvoker { args, _ ->
                    assertEquals(operations.removeFirstOrNull(), args[1], "Unexpected service effect")
                    if (args[1] == "bootout") LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                },
            socketProbe =
                BrokerSocketProbe { path ->
                    assertEquals(command.publicSocket, path)
                    assertEquals(0, probes++)
                    assertTrue(operations.isEmpty(), "Unconsumed service effects")
                    BrokerSocketReachability.UNREACHABLE
                },
            sleeper = BrokerServiceSleeper { throw AssertionError("Unexpected wait") },
        )
    }

    private fun unexpectedHost() =
        MacOsPersistentBrokerServiceHost(
            launchctl = LaunchctlInvoker { _, _ -> throw AssertionError("Unexpected service effect") },
            socketProbe = BrokerSocketProbe { throw AssertionError("Unexpected socket observation") },
            sleeper = BrokerServiceSleeper { throw AssertionError("Unexpected wait") },
        )

    private fun fixture(temporary: Path, environment: Script): Fixture {
        val root = Files.createDirectories(temporary.resolve("product")).toRealPath()
        val kast = executable(Files.createDirectories(root.resolve("bin")).resolve("kast"))
        executable(Files.createDirectories(root.resolve("share/kast/libexec")).resolve("kast-daemon"))
        val tools = Files.createDirectories(temporary.resolve("tools")).toRealPath()
        executable(tools.resolve("codex"))
        val next = executable(tools.resolve("codex-next"))
        val home = Files.createDirectories(temporary.resolve("home")).toRealPath()
        fun command(overrides: Map<String, String> = emptyMap()) =
            (BrokerServiceLaunchCommand.resolveCoordinator(
                    kast,
                    home,
                    mapOf("PATH" to tools.toString()) + selectedBrokerJbr(temporary) + overrides,
                ) as BrokerServiceLaunchCommandResolution.Resolved)
                .command
        val initial = command()
        val requested = command(mapOf("CODEX_EXECUTABLE" to next.toString()))
        assertNotEquals(initial.identity, requested.identity)
        Files.createDirectories(initial.stateDirectory)
        Files.setPosixFilePermissions(initial.stateDirectory, PosixFilePermissions.fromString("rwx------"))
        val receipt = initial.stateDirectory.resolve("service.plist")
        Files.writeString(receipt, BrokerLaunchdServiceDocument.render(initial))
        Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"))
        assertEquals(initial.identity, PublishedBrokerServiceCommand.recover(requested)?.identity)
        assertEquals(ServiceLoginAgentChange.READY, ServiceLoginAgent.publish(initial))
        writeServiceState(
            initial.readinessFile,
            BrokerServiceStateDocument.Ready(
                schemaVersion = BROKER_SERVICE_STATE_SCHEMA_VERSION,
                serviceIdentity = initial.identity.value,
                serviceInstanceId = "123e4567-e89b-42d3-a456-426614174000",
                brokerVersion = VENDORED_BROKER_VERSION,
            ),
        )
        val discovery = DesktopDaemonDiscovery(environment)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(DesktopDiscoveryTarget.from(initial)))
        return Fixture(initial, requested, discovery)
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }

    private data class Fixture(
        val initial: BrokerServiceLaunchCommand,
        val requested: BrokerServiceLaunchCommand,
        val discovery: DesktopDaemonDiscovery,
    ) {
        val record: Path
            get() = initial.stateDirectory.resolve("desktop-discovery/ownership.json")
    }

    private class Script(vararg steps: String) : DesktopDaemonEnvironment {
        private val remaining = ArrayDeque(steps.toList())

        override fun readHome(): DesktopDaemonHomeRead {
            assertEquals("home", remaining.removeFirstOrNull())
            return DesktopDaemonHomeRead.Default
        }

        override fun read(): DesktopDaemonEnvironmentRead {
            val next = remaining.removeFirstOrNull() ?: throw AssertionError("Unexpected flag observation")
            assertTrue(next.startsWith("read:"))
            return DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.valueOf(next.removePrefix("read:")))
        }

        override fun enable(): DesktopDiscoveryOutcome {
            assertEquals("enable", remaining.removeFirstOrNull())
            return DesktopDiscoveryOutcome.Ready
        }

        override fun remove(): DesktopDiscoveryOutcome {
            assertEquals("remove", remaining.removeFirstOrNull())
            return DesktopDiscoveryOutcome.Ready
        }

        fun exhausted() = assertTrue(remaining.isEmpty(), "Unconsumed flag effects: $remaining")
    }
}
