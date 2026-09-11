package io.github.amichne.kast.appserver

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PersistentBrokerServiceTest {
    @Test
    fun `coordinator service admission does not require an enabled or available Codex host`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)
        val result = BrokerServiceLaunchCommand.resolveCoordinator(fixture.kast, fixture.userHome,
            mapOf("PATH" to "/usr/bin:/bin", "KAST_ENABLE_APP_SERVER" to "0"))
        assertTrue(result is BrokerServiceLaunchCommandResolution.Resolved, result.toString())
        assertFalse(Files.exists(fixture.kast.parent.parent.resolve("state")))
    }

    @Test
    fun `installation lifecycle fence prevents launch inside service ownership lock`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)
        val command = resolvedCommand(fixture)
        Files.writeString(fixture.kast.parent.parent.resolve(".lifecycle-transition.json"), "{}")
        var calls = 0
        val host = MacOsPersistentBrokerServiceHost(launchctl = LaunchctlInvoker { _, _ ->
            calls += 1
            LaunchctlInvocation.Rejected
        })
        assertEquals(PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.DISABLED), host.ensure(command))
        assertEquals(0, calls)
    }

    @Test
    fun `installation lifecycle fence blocks login bootstrap before enrollment or stopped marker mutation`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)
        val root = fixture.kast.parent.parent
        Files.writeString(root.resolve(".lifecycle-transition.json"), "{}")
        val config = Files.createDirectories(root.resolve("config"))
        Files.writeString(config.resolve("workspaces.json"), "invalid")
        val stopped = Files.createDirectories(root.resolve("state/broker")).resolve("stopped")
        Files.writeString(stopped, "stopped")
        val manager = InstalledAppServerManager(fixture.kast, fixture.userHome, fixture.environment)
        assertEquals(AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN),
            manager.execute(AppServerAction.Bootstrap, fixture.userHome))
        assertTrue(Files.exists(stopped))
    }

    @Test
    fun `saved configuration disables persistent service without composing a runtime`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)
        val saved = Files.writeString(temporary.toRealPath().resolve("environment"), "KAST_ENABLE_APP_SERVER=0\n")
        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(PersistentBrokerServiceFailure.DISABLED),
            BrokerServiceLaunchCommand.resolve(fixture.kast, fixture.userHome,
                fixture.environment + ("KAST_CONFIGURATION_FILE" to saved.toString())),
        )
        assertTrue(!Files.exists(fixture.userHome.resolve(".codex")))
    }

    @Test
    fun `two physical installations sharing a host home have disjoint service ownership`(@TempDir temporary: Path) {
        val first = installedFixture(Files.createDirectory(temporary.resolve("first")))
        val second = installedFixture(Files.createDirectory(temporary.resolve("second")))
        val host = first.userHome.resolve(".codex")
        val a = resolvedCommand(first.copy(environment = first.environment + ("CODEX_HOME" to host.toString())))
        val b = resolvedCommand(second.copy(environment = second.environment + ("CODEX_HOME" to host.toString())))
        assertNotEquals(a.stateDirectory, b.stateDirectory)
        assertNotEquals(a.publicSocket, b.publicSocket)
        assertNotEquals(a.serviceLabel, b.serviceLabel)
        assertTrue(a.stateDirectory.startsWith(first.kast.parent.parent))
        assertFalse(a.stateDirectory.startsWith(host))
    }

    @Test
    fun `explicit stop fences future startup before retiring the owned service`(@TempDir temporary: Path) {
        val command = resolvedCommand(installedFixture(temporary))
        var present = true
        Files.createDirectories(command.stateDirectory)
        writeReadiness(command)
        val host = MacOsPersistentBrokerServiceHost(
            launchctl = LaunchctlInvoker { arguments, _ -> when (arguments[1]) {
                "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                "bootout" -> {
                    val uid = Files.getAttribute(command.userHome, "unix:uid") as Number
                    assertEquals("gui/${uid.toLong()}/${command.serviceLabel.value}", arguments[2])
                    assertTrue(Files.exists(command.stateDirectory.resolve("stopped")))
                    present = false
                    LaunchctlInvocation.Completed
                }
                else -> error("Startup must remain suppressed")
            } },
            socketProbe = BrokerSocketProbe { if (present) BrokerSocketReachability.REACHABLE else BrokerSocketReachability.UNREACHABLE },
            sleeper = BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
        )
        assertEquals(PersistentBrokerServiceAdmission.Ready,host.stop(command))
        assertFalse(present)
        assertEquals(PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.DISABLED),host.ensure(command))
        assertEquals(PersistentBrokerServiceAdmission.Ready,host.stop(command))
    }

    @Test
    fun `missing public socket is an absent broker rather than an indeterminate probe`(
        @TempDir temporary: Path,
    ) {
        assertEquals(
            BrokerSocketReachability.UNREACHABLE,
            JdkBrokerSocketProbe.probe(
                temporary.toRealPath().resolve("app-server-control.sock"),
            ),
        )
    }

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
    fun `raw Unix listener is not broker readiness`() {
        val temporary = Files.createTempDirectory(Path.of("/private/tmp"), "kb-probe.")
        val socket = temporary.resolve("raw.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val acceptor = thread(start = true) {
                server.accept().use { }
            }
            assertEquals(BrokerSocketReachability.REJECTED, JdkBrokerSocketProbe.probe(socket))
            acceptor.join(5_000)
        }
        temporary.toFile().deleteRecursively()
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

        assertEquals(tools.resolve("codex").toRealPath(), (resolution.command.host as BrokerHostSelection.Selected).executable.path)
        assertEquals(
            links.toRealPath().toString(),
            resolution.command.executableSearchPath.value.substringBefore(':'),
        )
    }

    @Test
    fun `Codex launcher directory participates in persistent service identity`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val tools = Path.of(fixture.environment.getValue("PATH"))
        val firstLinks = Files.createDirectory(temporary.resolve("first-links"))
        val secondLinks = Files.createDirectory(temporary.resolve("second-links"))
        Files.createSymbolicLink(firstLinks.resolve("codex"), tools.resolve("codex"))
        Files.createSymbolicLink(secondLinks.resolve("codex"), tools.resolve("codex"))

        val first = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            mapOf("PATH" to firstLinks.toString()),
        ) as BrokerServiceLaunchCommandResolution.Resolved
        val second = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            mapOf("PATH" to secondLinks.toString()),
        ) as BrokerServiceLaunchCommandResolution.Resolved

        assertNotEquals(first.command.identity, second.command.identity)
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
    fun `tool selection participates in persistent service identity`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val defaults = resolvedCommand(fixture)
        val queryOnly = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            fixture.environment + ("KAST_APP_SERVER_TOOLS" to "query_symbols"),
        ) as BrokerServiceLaunchCommandResolution.Resolved

        assertNotEquals(defaults.identity, queryOnly.command.identity)
        assertEquals(defaults.serviceLabel, queryOnly.command.serviceLabel)
        assertEquals("query_symbols", queryOnly.command.toolSelection.environmentValue)
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
                "bootstrap" -> {
                    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    val strings = factory.newDocumentBuilder().parse(Path.of(arguments.last()).toFile()).getElementsByTagName("string")
                    submission = (0 until strings.length).map { strings.item(it).textContent }
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
        assertTrue(submission.orEmpty().contains("KAST_ENABLE_APP_SERVER=1"))
        assertTrue(
            submission.orEmpty().contains(
                "KAST_RUNTIME_STORE=${fixture.userHome.resolve("runtime-store")}",
            ),
        )
        assertTrue(submission.orEmpty().contains("KAST_ENABLE_LAUNCHD=0"))
        assertTrue(
            submission.orEmpty().contains(
                "KAST_APP_SERVER_TOOLS=${command.toolSelection.environmentValue}",
            ),
        )
        assertTrue(
            submission.orEmpty().contains("PATH=${command.executableSearchPath.value}"),
        )
        assertTrue(submission.orEmpty().contains(command.serviceLabel.value))

        val launchEnvironment = command.stateDirectory.resolve("launch-environment")
        assertTrue(Files.isRegularFile(launchEnvironment))
        val launchLines = Files.readAllLines(launchEnvironment)
            .filterNot { it.startsWith("#") }
            .filter(String::isNotBlank)
        assertEquals(launchLines.sorted(), launchLines)
        val launched = launchLines.associate { line ->
            line.substringBefore('=') to line.substringAfter('=')
        }
        assertEquals("0", launched["KAST_DEBUG"])
        assertNotNull(launched["KAST_INDEXER_MAX_HEAP"])
        assertEquals("1", launched["KAST_WORKER_RESIDENT_LIMIT"])
        assertEquals(command.codexHome.toString(), launched["CODEX_HOME"])
        assertEquals(
            command.toolSelection.environmentValue,
            launched["KAST_APP_SERVER_TOOLS"],
        )
        assertFalse(launched.containsKey("KAST_OPTS"))
    }

    @Test
    fun `clean homes submit exactly one service through admitted socket proof`() {
        val temporary = Files.createTempDirectory(Path.of("/private/tmp"), "kb.")
        val fixture = installedFixture(temporary)
        val command = resolvedCommand(fixture)
        var present = false
        var submissions = 0
        val launchctl = LaunchctlInvoker { arguments, _ ->
            when (arguments[1]) {
                "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                "bootstrap" -> {
                    submissions += 1
                    Files.createDirectories(command.publicSocket.parent)
                    writeReadiness(command)
                    present = true
                    LaunchctlInvocation.Completed
                }
                else -> error("unexpected launchctl operation: ${arguments[1]}")
            }
        }
        try {
            val host = MacOsPersistentBrokerServiceHost(
                launchctl = launchctl,
                socketProbe = BrokerSocketProbe {
                    if (present) {
                        BrokerSocketReachability.REACHABLE
                    } else {
                        BrokerSocketReachability.UNREACHABLE
                    }
                },
                sleeper = BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
            )

            assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
            assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
            assertEquals(1, submissions)
        } finally {
            temporary.toFile().deleteRecursively()
        }
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
                    "bootstrap" -> {
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
                "bootout" -> {
                    val uid = Files.getAttribute(command.userHome, "unix:uid") as Number
                    assertEquals("gui/${uid.toLong()}/${command.serviceLabel.value}", arguments[2])
                    present = false
                    LaunchctlInvocation.Completed
                }
                "bootstrap" -> {
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
        assertTrue(operations.contains("bootout"))
        val recoveringHost = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe {
                if (ready) BrokerSocketReachability.REACHABLE else
                    BrokerSocketReachability.UNREACHABLE
            },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
        )
        assertEquals(PersistentBrokerServiceAdmission.Ready, recoveringHost.ensure(command))
        assertEquals(1, operations.count { it == "bootstrap" })
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
                "bootout" -> {
                    val uid = Files.getAttribute(command.userHome, "unix:uid") as Number
                    assertEquals("gui/${uid.toLong()}/${command.serviceLabel.value}", arguments[2])
                    present = false
                    LaunchctlInvocation.Completed
                }
                "bootstrap" -> {
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
        assertEquals(1, operations.count { it == "bootout" })
        assertEquals(1, operations.count { it == "bootstrap" })
    }

    @Test
    fun `malformed owned readiness is recovered in one ensure call`(
        @TempDir temporary: Path,
    ) {
        val command = resolvedCommand(installedFixture(temporary))
        var present = false
        val operations = mutableListOf<String>()
        val launchctl = LaunchctlInvoker { arguments, _ ->
            operations += arguments[1]
            when (arguments[1]) {
                "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                "bootout" -> {
                    present = false
                    LaunchctlInvocation.Completed
                }
                "bootstrap" -> {
                    present = true
                    writeReadiness(command)
                    LaunchctlInvocation.Completed
                }
                else -> error("unexpected launchctl operation: ${arguments[1]}")
            }
        }
        val host = MacOsPersistentBrokerServiceHost(
            launchctl,
            BrokerSocketProbe {
                if (present) BrokerSocketReachability.REACHABLE else BrokerSocketReachability.UNREACHABLE
            },
            BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
            retirementTimeoutNanos = TimeUnit.SECONDS.toNanos(1),
        )

        assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
        Files.writeString(command.readinessFile, "malformed")
        assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
        assertEquals(1, operations.count { it == "bootout" })
        assertEquals(2, operations.count { it == "bootstrap" })
    }

    @Test
    fun `orphaned malformed readiness is recovered when the service and socket are absent`(
        @TempDir temporary: Path,
    ) {
        val command = resolvedCommand(installedFixture(temporary))
        Files.createDirectories(command.readinessFile.parent)
        Files.writeString(command.readinessFile, "malformed")
        var present = false
        var submissions = 0
        val host = MacOsPersistentBrokerServiceHost(
            launchctl = LaunchctlInvoker { arguments, _ ->
                when (arguments[1]) {
                    "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                    "bootstrap" -> {
                        submissions += 1
                        present = true
                        writeReadiness(command)
                        LaunchctlInvocation.Completed
                    }
                    else -> error("unexpected launchctl operation: ${arguments[1]}")
                }
            },
            socketProbe = BrokerSocketProbe {
                if (present) BrokerSocketReachability.REACHABLE else BrokerSocketReachability.UNREACHABLE
            },
            sleeper = BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
        )

        assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
        assertEquals(1, submissions)
    }

    @Test
    fun `destructive recovery removes only the exact installation state tree`(
        @TempDir temporary: Path,
    ) {
        val command = resolvedCommand(installedFixture(temporary))
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
        val host = MacOsPersistentBrokerServiceHost(
            launchctl = LaunchctlInvoker { arguments, _ ->
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
    fun `attempt-correlated child rejection reaches the initiating demand exactly`(
        @TempDir temporary: Path,
    ) {
        val command = resolvedCommand(installedFixture(temporary))
        var present = true
        val operations = mutableListOf<String>()
        Files.createDirectories(command.readinessFile.parent)
        Files.writeString(
            command.readinessFile,
            """{"state":"rejected","schemaVersion":$BROKER_SERVICE_STATE_SCHEMA_VERSION,"serviceIdentity":"${command.identity.value}","serviceInstanceId":"123e4567-e89b-42d3-a456-426614174000","brokerVersion":"$VENDORED_BROKER_VERSION","failure":"CODEX_QUALIFICATION_REJECTED"}""",
        )
        val launchctl = LaunchctlInvoker { arguments, _ ->
            operations += arguments[1]
            when (arguments[1]) {
                "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                "bootout" -> {
                    val uid = Files.getAttribute(command.userHome, "unix:uid") as Number
                    assertEquals("gui/${uid.toLong()}/${command.serviceLabel.value}", arguments[2])
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
        assertEquals(listOf("list", "list", "bootout", "list"), operations)
        assertEquals(false, Files.exists(command.readinessFile))
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
            mapOf(
                "PATH" to tools.toString(),
                "KAST_RUNTIME_STORE" to userHome.resolve("runtime-store").toString(),
                "KAST_RUNTIME_DIRECTORY" to userHome.resolve("runtime").toString(),
                "KAST_CACHE_ROOT" to userHome.resolve("cache").toString(),
                "KAST_ENABLE_LAUNCHD" to "0",
            ),
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
        """{"state":"ready","schemaVersion":$BROKER_SERVICE_STATE_SCHEMA_VERSION,"serviceIdentity":"${command.identity.value}","serviceInstanceId":"123e4567-e89b-42d3-a456-426614174000","brokerVersion":"$VENDORED_BROKER_VERSION"}"""

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
