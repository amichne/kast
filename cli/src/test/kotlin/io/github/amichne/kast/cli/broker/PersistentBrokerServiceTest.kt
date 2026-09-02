package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.CanonicalRootDiscovery
import io.github.amichne.kast.cli.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.HostedRuntimeDemand
import io.github.amichne.kast.cli.LaunchctlInvocation
import io.github.amichne.kast.cli.LaunchctlInvoker
import io.github.amichne.kast.cli.RootRuntimeDemander
import io.github.amichne.kast.cli.RuntimeAdmission
import io.github.amichne.kast.cli.RuntimeAdmissionFailure
import io.github.amichne.kast.cli.RuntimeStartupRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PersistentBrokerServiceTest {
    @Test
    fun `startup and lock deadlines strictly contain every admitted child phase`() {
        assertTrue(
            BrokerServiceStartupBudgets.admittedChildPhasesNanos <
                BrokerServiceStartupBudgets.hostTimeoutNanos,
        )
        assertTrue(
            BrokerServiceStartupBudgets.hostTimeoutNanos +
                BrokerServiceStartupBudgets.retirementTimeoutNanos <
                BrokerServiceStartupBudgets.lockTimeoutNanos,
        )
    }

    @Test
    fun `active runtime demand proves broker readiness before sidecar demand`(
        @TempDir temporary: Path,
    ) {
        val root = root(temporary)
        val events = mutableListOf<String>()
        val expected = RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
        val delegate = rootRuntimeDemander { _, _, _ ->
            events += "sidecar"
            expected
        }
        val demander = BrokerEnsuringRootRuntimeDemander(
            PersistentBrokerService {
                events += "broker"
                PersistentBrokerServiceAdmission.Ready
            },
            delegate,
        )

        assertEquals(
            expected,
            demander.demand(
                root,
                HostedRuntimeDemand.Lifecycle,
                RuntimeStartupRequest.Default,
            ),
        )
        assertEquals(listOf("broker", "sidecar"), events)
    }

    @Test
    fun `broker rejection prevents every sidecar effect and preserves finite cause`(
        @TempDir temporary: Path,
    ) {
        val root = root(temporary)
        var sidecarInvoked = false
        val failure = PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE
        val demander = BrokerEnsuringRootRuntimeDemander(
            PersistentBrokerService { PersistentBrokerServiceAdmission.Rejected(failure) },
            rootRuntimeDemander { _, _, _ ->
                sidecarInvoked = true
                error("sidecar must remain unreachable")
            },
        )

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.PersistentBroker(failure)),
            demander.demand(
                root,
                HostedRuntimeDemand.Lifecycle,
                RuntimeStartupRequest.Default,
            ),
        )
        assertEquals(false, sidecarInvoked)
    }

    @Test
    fun `installed service refines exact executable identity once`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        var captured: BrokerServiceLaunchCommand? = null
        val service = InstalledPersistentBrokerService(
            fixture.kast,
            fixture.userHome,
            fixture.environment,
            PersistentBrokerServiceHost { command ->
                captured = command
                PersistentBrokerServiceAdmission.Ready
            },
        )

        assertEquals(PersistentBrokerServiceAdmission.Ready, service.ensure())
        assertNotNull(captured)
        val command = checkNotNull(captured)
        assertEquals(fixture.kast.toRealPath(), command.kast)
        assertTrue(command.identity.value.startsWith("sha256:"))
        assertTrue(Files.isExecutable(command.javaExecutable))
    }

    @Test
    fun `explicit invalid codex executable fails closed instead of searching path`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)

        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(
                PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE,
            ),
            BrokerServiceLaunchCommand.resolve(
                fixture.kast,
                fixture.userHome,
                fixture.environment + ("CODEX_EXECUTABLE" to "relative/codex"),
            ),
        )
    }

    @Test
    fun `explicit invalid codex home fails closed instead of using user default`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)

        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(
                PersistentBrokerServiceFailure.CODEX_HOME_REJECTED,
            ),
            BrokerServiceLaunchCommand.resolve(
                fixture.kast,
                fixture.userHome,
                fixture.environment + ("CODEX_HOME" to "relative-codex-home"),
            ),
        )
    }

    @Test
    fun `invalid user home has its own finite failure`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)

        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(
                PersistentBrokerServiceFailure.USER_HOME_REJECTED,
            ),
            BrokerServiceLaunchCommand.resolve(
                fixture.kast,
                temporary.resolve("missing-home"),
                fixture.environment,
            ),
        )
    }

    @Test
    fun `homebrew style executable symlink refines to physical codex target`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val links = Files.createDirectory(temporary.resolve("tool-links"))
        val tools = Path.of(fixture.environment.getValue("PATH"))
        Files.createSymbolicLink(links.resolve("codex"), tools.resolve("codex"))

        val resolution = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            mapOf("PATH" to links.toString()),
        ) as BrokerServiceLaunchCommandResolution.Resolved

        assertEquals(tools.resolve("codex").toRealPath(), resolution.command.codex)
    }

    @Test
    fun `identity includes user home while label remains scoped to codex home`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val otherHome = Files.createDirectory(temporary.resolve("other-home")).toRealPath()
        val sharedCodexHome = temporary.resolve("shared-codex-home")
        val environment = fixture.environment + ("CODEX_HOME" to sharedCodexHome.toString())
        val first = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            environment,
        ) as BrokerServiceLaunchCommandResolution.Resolved
        val second = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            otherHome,
            environment,
        ) as BrokerServiceLaunchCommandResolution.Resolved

        assertNotEquals(first.command.identity, second.command.identity)
        assertEquals(first.command.serviceLabel, second.command.serviceLabel)
    }

    @Test
    fun `launchd submission waits for matching child-published readiness`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val command = when (val resolution = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            fixture.environment,
        )) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command
            is BrokerServiceLaunchCommandResolution.Rejected -> error(resolution.failure)
        }
        var present = false
        var submission: List<String>? = null
        val launchctl = LaunchctlInvoker { arguments, _ ->
            when (arguments[1]) {
                "list" -> if (present) {
                    LaunchctlInvocation.Completed
                } else {
                    LaunchctlInvocation.Absent
                }
                "submit" -> {
                    submission = arguments
                    present = true
                    Files.writeString(
                        command.readinessFile,
                        readyStateDocument(command),
                    )
                    LaunchctlInvocation.Completed
                }
                else -> error("unexpected launchctl operation: ${arguments[1]}")
            }
        }
        val host = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe {
                if (present) BrokerSocketReachability.REACHABLE else
                    BrokerSocketReachability.UNREACHABLE
            },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
        )

        assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
        assertTrue(submission.orEmpty().contains(command.kast.toString()))
        assertTrue(submission.orEmpty().contains("broker"))
        assertTrue(submission.orEmpty().contains("serve"))
        assertTrue(
            submission.orEmpty().contains(
                "BROKER_SERVICE_IDENTITY=${command.identity.value}",
            ),
        )
        assertTrue(submission.orEmpty().contains("JAVA_HOME=${command.javaHome}"))
        assertTrue(submission.orEmpty().contains("KAST_OPTS=${command.jvmUserHomeOption.value}"))
        assertTrue(submission.orEmpty().contains(command.serviceLabel.value))
    }

    @Test
    fun `concurrent first use submits exactly one service under the filesystem lock`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val command = resolvedCommand(fixture)
        val start = CountDownLatch(1)
        var present = false
        var submissions = 0
        val launchctl = LaunchctlInvoker { arguments, _ ->
            synchronized(command) {
                when (arguments[1]) {
                    "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                    "submit" -> {
                        submissions += 1
                        present = true
                        writeReadiness(command)
                        LaunchctlInvocation.Completed
                    }
                    else -> error("unexpected launchctl operation: ${arguments[1]}")
                }
            }
        }
        val host = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe {
                synchronized(command) {
                    if (present) BrokerSocketReachability.REACHABLE else
                        BrokerSocketReachability.UNREACHABLE
                }
            },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val admissions = (1..2).map {
                executor.submit<PersistentBrokerServiceAdmission> {
                    start.await()
                    host.ensure(command)
                }
            }
            start.countDown()

            assertEquals(
                listOf(
                    PersistentBrokerServiceAdmission.Ready,
                    PersistentBrokerServiceAdmission.Ready,
                ),
                admissions.map { future -> future.get(5, TimeUnit.SECONDS) },
            )
            assertEquals(1, submissions)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `missing readiness crash loop is retired after the startup bound`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val command = resolvedCommand(fixture)
        var present = true
        var ready = false
        val operations = mutableListOf<String>()
        val launchctl = LaunchctlInvoker { arguments, _ ->
            operations += arguments[1]
            when (arguments[1]) {
                "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                "remove" -> {
                    present = false
                    LaunchctlInvocation.Completed
                }
                "submit" -> {
                    present = true
                    ready = true
                    writeReadiness(command)
                    LaunchctlInvocation.Completed
                }
                else -> error("unexpected launchctl operation: ${arguments[1]}")
            }
        }
        val host = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe {
                if (ready) BrokerSocketReachability.REACHABLE else
                    BrokerSocketReachability.UNREACHABLE
            },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
            startupTimeoutNanos = 1L,
        )

        assertEquals(
            PersistentBrokerServiceAdmission.Rejected(
                PersistentBrokerServiceFailure.STARTUP_TIMED_OUT,
            ),
            host.ensure(command),
        )
        assertTrue(operations.contains("remove"))
        val recoveringHost = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe {
                if (ready) BrokerSocketReachability.REACHABLE else
                    BrokerSocketReachability.UNREACHABLE
            },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
        )
        assertEquals(PersistentBrokerServiceAdmission.Ready, recoveringHost.ensure(command))
        assertEquals(1, operations.count { it == "submit" })
    }

    @Test
    fun `stale crash readiness is fenced and recovered in one ensure call`(
        @TempDir temporary: Path,
    ) {
        val command = resolvedCommand(installedFixture(temporary))
        var present = true
        var replacementReady = false
        val operations = mutableListOf<String>()
        writeReadiness(command)
        val launchctl = LaunchctlInvoker { arguments, _ ->
            operations += arguments[1]
            when (arguments[1]) {
                "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                "remove" -> {
                    present = false
                    LaunchctlInvocation.Completed
                }
                "submit" -> {
                    present = true
                    replacementReady = true
                    writeReadiness(command)
                    LaunchctlInvocation.Completed
                }
                else -> error("unexpected launchctl operation: ${arguments[1]}")
            }
        }
        val host = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe {
                if (replacementReady) BrokerSocketReachability.REACHABLE else
                    BrokerSocketReachability.UNREACHABLE
            },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
            retirementTimeoutNanos = TimeUnit.SECONDS.toNanos(1),
        )

        assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
        assertEquals(1, operations.count { it == "remove" })
        assertEquals(1, operations.count { it == "submit" })
    }

    @Test
    fun `attempt-correlated child rejection reaches the initiating demand exactly`(
        @TempDir temporary: Path,
    ) {
        val command = resolvedCommand(installedFixture(temporary))
        var present = true
        val operations = mutableListOf<String>()
        Files.createDirectories(command.readinessFile.parent)
        Files.writeString(
            command.readinessFile,
            """{"state":"rejected","schemaVersion":2,"serviceIdentity":"${command.identity.value}","serviceInstanceId":"123e4567-e89b-42d3-a456-426614174000","brokerVersion":"$VENDORED_BROKER_VERSION","failure":"CODEX_QUALIFICATION_REJECTED"}""",
        )
        val launchctl = LaunchctlInvoker { arguments, _ ->
            operations += arguments[1]
            when (arguments[1]) {
                "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                "remove" -> {
                    present = false
                    LaunchctlInvocation.Completed
                }
                else -> error("unexpected launchctl operation: ${arguments[1]}")
            }
        }
        val host = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe { BrokerSocketReachability.UNREACHABLE },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
            retirementTimeoutNanos = TimeUnit.SECONDS.toNanos(1),
        )

        assertEquals(
            PersistentBrokerServiceAdmission.Rejected(
                PersistentBrokerServiceFailure.CODEX_QUALIFICATION_REJECTED,
            ),
            host.ensure(command),
        )
        assertEquals(listOf("list", "list", "remove", "list"), operations)
        assertEquals(false, Files.exists(command.readinessFile))
    }

    private fun root(temporary: Path): CanonicalRoot {
        val workspace = Files.createDirectories(temporary.resolve("workspace"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"test\"")
        return when (val discovery = FilesystemCanonicalRootDiscovery.discover(workspace)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> error(discovery.failure)
        }
    }

    private fun rootRuntimeDemander(
        operation: (CanonicalRoot, HostedRuntimeDemand, RuntimeStartupRequest) -> RuntimeAdmission,
    ): RootRuntimeDemander = object : RootRuntimeDemander {
        override fun demand(
            root: CanonicalRoot,
            demand: HostedRuntimeDemand,
            startup: RuntimeStartupRequest,
        ): RuntimeAdmission = operation(root, demand, startup)
    }

    private fun installedFixture(temporary: Path): InstalledFixture {
        val product = Files.createDirectories(temporary.resolve("product")).toRealPath()
        val bin = Files.createDirectories(product.resolve("bin"))
        val kast = executable(bin.resolve("kast"), "#!/bin/sh\nexit 0\n")
        val tools = Files.createDirectories(temporary.resolve("tools")).toRealPath()
        executable(tools.resolve("codex"), "#!/bin/sh\nexit 0\n")
        val userHome = Files.createDirectories(temporary.resolve("home")).toRealPath()
        return InstalledFixture(
            kast,
            userHome,
            mapOf("PATH" to tools.toString()),
        )
    }

    private fun resolvedCommand(fixture: InstalledFixture): BrokerServiceLaunchCommand = when (
        val resolution = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            fixture.environment,
        )
    ) {
        is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command
        is BrokerServiceLaunchCommandResolution.Rejected -> error(resolution.failure)
    }

    private fun writeReadiness(command: BrokerServiceLaunchCommand) {
        Files.createDirectories(command.readinessFile.parent)
        Files.writeString(command.readinessFile, readyStateDocument(command))
    }

    private fun readyStateDocument(command: BrokerServiceLaunchCommand): String =
        """{"state":"ready","schemaVersion":2,"serviceIdentity":"${command.identity.value}","serviceInstanceId":"123e4567-e89b-42d3-a456-426614174000","brokerVersion":"$VENDORED_BROKER_VERSION"}"""

    private fun executable(path: Path, content: String): Path {
        Files.writeString(path, content)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}

private data class InstalledFixture(
    val kast: Path,
    val userHome: Path,
    val environment: Map<String, String>,
)
