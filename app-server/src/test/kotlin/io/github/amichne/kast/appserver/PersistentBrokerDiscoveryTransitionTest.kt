package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PersistentBrokerDiscoveryTransitionTest {
    @Test
    fun `identity rotation releases predecessor discovery before replacement publication`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val replacement =
            fixture.command(mapOf("CODEX_EXECUTABLE" to executable(fixture.tools.resolve("codex-next")).toString()))
        rotate(fixture.command(), replacement, true)
    }

    @Test
    fun `private replacement withdraws predecessor discovery before submission`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        rotate(fixture.command(), fixture.command(mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private")), false)
    }

    @Test
    fun `missing readiness recovers discovery owner from predecessor receipt`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        rotate(
            fixture.command(),
            fixture.command(mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private")),
            false,
            PredecessorState.RECEIPT_ONLY,
        )
    }

    @Test
    fun `failed cleanup preserves predecessor receipt and blocks replacement`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val initial = fixture.command()
        val replacement = fixture.command(mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private"))
        Files.createDirectories(initial.stateDirectory)
        Files.setPosixFilePermissions(initial.stateDirectory, PosixFilePermissions.fromString("rwx------"))
        val receipt = initial.stateDirectory.resolve("service.plist")
        val document = BrokerLaunchdServiceDocument.render(initial)
        Files.writeString(receipt, document)
        Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"))
        val environment =
            EnvironmentScript(
                listOf(
                    "home",
                    "read:ABSENT",
                    "enable",
                    "read:ENABLED",
                    "read:ENABLED",
                    "remove:REJECTED",
                )
            )
        val discovery = DesktopDaemonDiscovery(environment)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(DesktopDiscoveryTarget.from(initial)))
        val record = initial.stateDirectory.resolve("desktop-discovery/ownership.json")
        val operations = ArrayDeque(listOf("list-absent"))
        val probes = ArrayDeque(listOf(BrokerSocketReachability.UNREACHABLE))
        val host = replacementHost(replacement, discovery, record, operations, probes)
        assertEquals(
            PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.DESKTOP_DISCOVERY_REJECTED),
            host.ensure(replacement),
        )
        assertEquals(document, Files.readString(receipt))
        assertTrue(Files.exists(record))
        assertFalse(Files.exists(replacement.readinessFile))
        assertTrue(operations.isEmpty(), "Unconsumed launchd operations")
        assertTrue(probes.isEmpty(), "Unconsumed socket observations")
        environment.exhausted()
    }

    private enum class PredecessorState {
        LIVE,
        RECEIPT_ONLY,
    }

    private fun rotate(
        initial: BrokerServiceLaunchCommand,
        replacement: BrokerServiceLaunchCommand,
        republishes: Boolean,
        predecessorState: PredecessorState = PredecessorState.LIVE,
    ) {
        assertNotEquals(initial.identity, replacement.identity)
        assertEquals(initial.stateDirectory, replacement.stateDirectory)
        Files.createDirectories(initial.stateDirectory)
        Files.setPosixFilePermissions(initial.stateDirectory, PosixFilePermissions.fromString("rwx------"))
        val environment =
            EnvironmentScript(
                listOf("home", "read:ABSENT", "enable", "read:ENABLED", "read:ENABLED", "remove", "read:ABSENT") +
                    if (republishes)
                        listOf("home", "read:ABSENT", "enable", "read:ENABLED", "read:ENABLED", "remove", "read:ABSENT")
                    else emptyList()
            )
        val discovery = DesktopDaemonDiscovery(environment)
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.enable(DesktopDiscoveryTarget.from(initial)))
        val receipt = initial.stateDirectory.resolve("service.plist")
        Files.writeString(receipt, BrokerLaunchdServiceDocument.render(initial))
        Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"))
        assertEquals(initial.identity, PublishedBrokerServiceCommand.recover(replacement)?.identity)
        if (predecessorState == PredecessorState.LIVE) writeReady(initial)
        val record = initial.stateDirectory.resolve("desktop-discovery/ownership.json")
        val operations =
            ArrayDeque(
                when (predecessorState) {
                    PredecessorState.LIVE -> listOf("list", "bootout", "list-absent", "bootstrap", "list")
                    PredecessorState.RECEIPT_ONLY -> listOf("list-absent", "bootstrap", "list")
                }
            )
        val probes = ArrayDeque(listOf(BrokerSocketReachability.UNREACHABLE, BrokerSocketReachability.REACHABLE))
        val host = replacementHost(replacement, discovery, record, operations, probes)
        assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(replacement))
        assertTrue(operations.isEmpty(), "Unconsumed launchd operations")
        assertTrue(probes.isEmpty(), "Unconsumed socket observations")
        assertEquals(republishes, Files.exists(record))
        assertEquals(DesktopDiscoveryOutcome.Ready, discovery.release(DesktopDiscoveryTarget.from(replacement)))
        assertFalse(Files.exists(record))
        environment.exhausted()
    }

    private fun replacementHost(
        replacement: BrokerServiceLaunchCommand,
        discovery: DesktopDaemonDiscovery,
        record: Path,
        operations: ArrayDeque<String>,
        probes: ArrayDeque<BrokerSocketReachability>,
    ): MacOsPersistentBrokerServiceHost {
        return MacOsPersistentBrokerServiceHost(
            launchctl =
                LaunchctlInvoker { arguments, _ ->
                    val operation = operations.removeFirstOrNull() ?: unexpected("Unexpected launchd operation")
                    assertEquals(operation.substringBefore('-'), arguments[1], "Unexpected launchd operation")
                    when (operation) {
                        "list" -> LaunchctlInvocation.Completed
                        "list-absent" -> LaunchctlInvocation.Absent
                        "bootout" -> LaunchctlInvocation.Completed
                        "bootstrap" -> {
                            assertFalse(Files.exists(record), "Predecessor discovery must retire before submission")
                            assertEquals(
                                DesktopDiscoveryOutcome.Ready,
                                discovery.enable(DesktopDiscoveryTarget.from(replacement)),
                            )
                            writeReady(replacement)
                            LaunchctlInvocation.Completed
                        }
                        else -> unexpected("Unexpected launchd operation")
                    }
                },
            socketProbe =
                BrokerSocketProbe {
                    probes.removeFirstOrNull() ?: unexpected("Unexpected socket observation")
                },
            sleeper = BrokerServiceSleeper { unexpected("Unexpected wait") },
            desktopDiscovery = discovery,
        )
    }

    private fun unexpected(message: String): Nothing = throw AssertionError(message)

    private fun writeReady(command: BrokerServiceLaunchCommand) =
        writeServiceState(
            command.readinessFile,
            BrokerServiceStateDocument.Ready(
                BROKER_SERVICE_STATE_SCHEMA_VERSION,
                command.identity.value,
                "123e4567-e89b-42d3-a456-426614174000",
                VENDORED_BROKER_VERSION,
            ),
        )

    private fun fixture(temporary: Path): Fixture {
        val root = Files.createDirectories(temporary.resolve("product")).toRealPath()
        val kast = executable(Files.createDirectories(root.resolve("bin")).resolve("kast"))
        executable(Files.createDirectories(root.resolve("share/kast/libexec")).resolve("kast-daemon"))
        val tools = Files.createDirectories(temporary.resolve("tools")).toRealPath()
        executable(tools.resolve("codex"))
        return Fixture(kast, Files.createDirectories(temporary.resolve("home")).toRealPath(), tools)
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }

    private class Fixture(val kast: Path, val home: Path, val tools: Path) {
        fun command(overrides: Map<String, String> = emptyMap()): BrokerServiceLaunchCommand =
            when (
                val result =
                    BrokerServiceLaunchCommand.resolveCoordinator(
                        kast,
                        home,
                        mapOf("PATH" to tools.toString()) + selectedBrokerJbr(home) + overrides,
                    )
            ) {
                is BrokerServiceLaunchCommandResolution.Resolved -> result.command
                is BrokerServiceLaunchCommandResolution.Rejected -> throw AssertionError(result.failure)
            }
    }

    private class EnvironmentScript(steps: List<String>) : DesktopDaemonEnvironment {
        private val remaining = ArrayDeque(steps)

        override fun readHome(): DesktopDaemonHomeRead {
            assertEquals("home", remaining.removeFirstOrNull(), "Unexpected home observation")
            return DesktopDaemonHomeRead.Default
        }

        override fun read(): DesktopDaemonEnvironmentRead {
            val step = remaining.removeFirstOrNull() ?: throw AssertionError("Unexpected environment observation")
            assertTrue(step.startsWith("read:"), "Unexpected read: $step")
            return DesktopDaemonEnvironmentRead.Observed(DesktopDaemonSetting.valueOf(step.removePrefix("read:")))
        }

        override fun enable(): DesktopDiscoveryOutcome {
            assertEquals("enable", remaining.removeFirstOrNull(), "Unexpected publication")
            return DesktopDiscoveryOutcome.Ready
        }

        override fun remove(): DesktopDiscoveryOutcome {
            return when (remaining.removeFirstOrNull()) {
                "remove" -> DesktopDiscoveryOutcome.Ready
                "remove:REJECTED" -> DesktopDiscoveryOutcome.Rejected(DesktopDiscoveryFailure.COMMAND_REJECTED)
                else -> throw AssertionError("Unexpected cleanup")
            }
        }

        fun exhausted() = assertTrue(remaining.isEmpty(), "Unconsumed environment operations: $remaining")
    }
}
