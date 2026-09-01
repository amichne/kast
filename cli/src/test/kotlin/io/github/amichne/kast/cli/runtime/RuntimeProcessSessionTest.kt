package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

class RuntimeProcessSessionTest {
    @Test
    fun `demander waits for endpoint while exact session is between launch attempts`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        var probes = 0
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = launchContext(temporary),
            processStarter = RuntimeProcessStarter {
                RuntimeProcessStart.Accepted(
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Present },
                    RuntimeProcessStartOrigin.EXISTING_SESSION,
                )
            },
            endpointProbe = RuntimeEndpointProbe {
                probes += 1
                if (probes < 3) {
                    RuntimeEndpointReachability.Unreachable
                } else {
                    RuntimeEndpointReachability.Reachable
                }
            },
        )

        assertEquals(RuntimeAdmission.Ready(endpoint), demander.demand(endpoint.root, endpoint))
        assertEquals(3, probes)
    }

    @Test
    fun `accepted session ending before reachability is a finite startup failure`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        var probes = 0
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = launchContext(temporary),
            processStarter = RuntimeProcessStarter {
                RuntimeProcessStart.Accepted(
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Absent },
                    RuntimeProcessStartOrigin.STARTED,
                )
            },
            endpointProbe = RuntimeEndpointProbe {
                probes += 1
                RuntimeEndpointReachability.Unreachable
            },
        )

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.SessionEndedBeforeReady),
            demander.demand(endpoint.root, endpoint),
        )
        assertEquals(2, probes, "terminal session loss must not exhaust the startup bound")
    }

    @Test
    fun `startup session observation failure and interruption remain distinct`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        fun demand(observation: RuntimeSessionObservation): RuntimeAdmission =
            ExactRootProcessRuntimeDemander(
                executable = executable(temporary),
                launchContext = launchContext(temporary),
                processStarter = RuntimeProcessStarter {
                    RuntimeProcessStart.Accepted(
                        AcceptedRuntimeStartupSession { observation },
                        RuntimeProcessStartOrigin.STARTED,
                    )
                },
                endpointProbe = RuntimeEndpointProbe {
                    RuntimeEndpointReachability.Unreachable
                },
            ).demand(endpoint.root, endpoint)

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ProcessObservationFailed),
            demand(RuntimeSessionObservation.Rejected),
        )
        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted),
            demand(RuntimeSessionObservation.Interrupted),
        )
    }

    @Test
    fun `existing exact launchd session is accepted without duplicate submission`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val invocations = mutableListOf<String>()
        val session = MacOsRuntimeProcessSession.from(
            endpoint,
            LaunchctlInvoker { arguments, _ ->
                invocations += arguments[1]
                LaunchctlInvocation.Completed
            },
        )

        assertEquals(
            RuntimeProcessStart.Accepted(
                session,
                RuntimeProcessStartOrigin.EXISTING_SESSION,
            ),
            session.start(command(temporary, endpoint)),
        )
        assertEquals(listOf("list"), invocations)
    }

    @Test
    fun `session that wins a rejected submission race is accepted`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val invocations = mutableListOf<String>()
        var observations = 0
        val session = MacOsRuntimeProcessSession.from(
            endpoint,
            LaunchctlInvoker { arguments, _ ->
                val operation = arguments[1]
                invocations += operation
                when (operation) {
                    "list" -> if (observations++ == 0) {
                        LaunchctlInvocation.Absent
                    } else {
                        LaunchctlInvocation.Completed
                    }
                    "submit" -> LaunchctlInvocation.Rejected
                    else -> error("unexpected launchctl operation: $operation")
                }
            },
        )

        assertEquals(
            RuntimeProcessStart.Accepted(
                session,
                RuntimeProcessStartOrigin.EXISTING_SESSION,
            ),
            session.start(command(temporary, endpoint)),
        )
        assertEquals(listOf("list", "submit", "list"), invocations)
    }

    @Test
    fun `unproven and interrupted launchd observations remain closed start failures`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val command = command(temporary, endpoint)
        val rejected = MacOsRuntimeProcessSession.from(
            endpoint,
            LaunchctlInvoker { _, _ -> LaunchctlInvocation.Rejected },
        )
        val interrupted = MacOsRuntimeProcessSession.from(
            endpoint,
            LaunchctlInvoker { _, _ -> LaunchctlInvocation.Interrupted },
        )

        assertEquals(RuntimeProcessStart.Rejected, rejected.start(command))
        assertEquals(RuntimeProcessStart.Interrupted, interrupted.start(command))
    }

    @Test
    fun `launchd label remains owned while its process is between restart attempts`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        var retired = false
        val processSession = object : RuntimeProcessSession {
            override fun observe(): RuntimeSessionObservation =
                RuntimeSessionObservation.Present

            override fun retire(
                present: RuntimeSessionObservation.Present,
            ): RuntimeSessionRetirement = when (present) {
                RuntimeSessionObservation.Present -> {
                    retired = true
                    RuntimeSessionRetirement.Retired
                }
            }
        }
        val authority = ExactRuntimeProcessAuthority(
            processSearch = RuntimeProcessSearch { RuntimeProcessSearchResult.None },
            processSessions = RuntimeProcessSessionResolver { processSession },
        )

        val owned = assertInstanceOf(
            RuntimeProcessObservation.Owned::class.java,
            authority.observe(endpoint),
        )

        assertEquals(RuntimeProcessTermination.Terminated, owned.process.terminate())
        assertTrue(retired, "stop must remove the owned launchd label before marker retirement")
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `direct launch starts an observable child without a launchd service`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val pidFile = endpoint.socketPath.resolveSibling("${endpoint.socketPath.fileName}.pid")
        val serviceFile = endpoint.socketPath.resolveSibling("${endpoint.socketPath.fileName}.service")

        val start = JdkRuntimeProcessStarter.start(command(temporary, endpoint))
        val accepted = assertInstanceOf(RuntimeProcessStart.Accepted::class.java, start)
        assertEquals(RuntimeProcessStartOrigin.STARTED, accepted.origin)
        awaitFile(pidFile)
        awaitFile(serviceFile)
        val process = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()

        try {
            assertEquals("", Files.readString(serviceFile).trim())
            assertEquals(RuntimeSessionObservation.Present, accepted.session.observe())
        } finally {
            retireDirectProcess(process, serviceFile)
        }
        assertEquals(RuntimeSessionObservation.Absent, accepted.session.observe())
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `launchd opt-in leaves the initiating caller process group`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val pidFile = endpoint.socketPath.resolveSibling("${endpoint.socketPath.fileName}.pid")
        val serviceFile = endpoint.socketPath.resolveSibling("${endpoint.socketPath.fileName}.service")
        val command = command(temporary, endpoint)
        val session = command.processSession

        val start = LaunchdRuntimeProcessStarter.start(command)
        check(
            start is RuntimeProcessStart.Accepted &&
                start.origin == RuntimeProcessStartOrigin.STARTED
        ) { "runtime process did not start: $start" }
        awaitFile(pidFile)
        awaitFile(serviceFile)
        val process = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()

        try {
            assertEquals(NO_LAUNCHD_SERVICE, Files.readString(serviceFile).trim())
            assertNotEquals(
                processGroup(ProcessHandle.current().pid()),
                processGroup(process.pid()),
                "runtime process must leave the initiating caller's process group",
            )
        } finally {
            assertEquals(RuntimeSessionObservation.Present, session.observe())
            assertEquals(
                RuntimeSessionRetirement.Retired,
                session.retire(RuntimeSessionObservation.Present),
            )
            process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `failed child retires its launchd session instead of becoming restartable`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val serviceFile = endpoint.socketPath.resolveSibling("${endpoint.socketPath.fileName}.failed-service")
        val session = MacOsRuntimeProcessSession.from(endpoint)
        val start = session.start(failingCommand(temporary, endpoint, serviceFile))
        check(
            start is RuntimeProcessStart.Accepted &&
                start.origin == RuntimeProcessStartOrigin.STARTED
        ) { "runtime process did not start: $start" }
        awaitFile(serviceFile)

        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            var observation = session.observe()
            while (
                observation == RuntimeSessionObservation.Present &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(25)
                observation = session.observe()
            }
            assertEquals(
                RuntimeSessionObservation.Absent,
                observation,
                "A failed child must not leave launchd with permission to restart it",
            )
        } finally {
            if (session.observe() == RuntimeSessionObservation.Present) {
                session.retire(RuntimeSessionObservation.Present)
            }
        }
    }

    private fun endpoint(temporary: Path): RuntimeEndpoint {
        val rootPath = Files.createDirectory(temporary.resolve("workspace"))
        Files.writeString(rootPath.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = when (val discovery = FilesystemCanonicalRootDiscovery.discover(rootPath)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> error(discovery.failure)
        }
        val runtimeId = when (
            val parsed = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}")
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error(parsed.failure)
        }
        return when (
            val resolution = RuntimeEndpoint.at(root, runtimeId, temporary.resolve("runtime.sock"))
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> error(resolution.failure)
        }
    }

    private fun executable(temporary: Path): IndexerExecutable {
        val executable = temporary.resolve(INDEXER_FIXTURE_NAME)
        Files.writeString(
            executable,
            """#!/bin/bash
                |set -euo pipefail
                |socket_path=""
                |for argument in "${'$'}@"; do
                |  case "${'$'}argument" in
                |    --socket-path=*) socket_path="${'$'}{argument#--socket-path=}" ;;
                |  esac
                |done
                |printf '%s\n' "${'$'}${'$'}" > "${'$'}{socket_path}.pid"
                |printf '%s\n' "${'$'}{XPC_SERVICE_NAME:-}" > "${'$'}{socket_path}.service"
                |trap 'exit 0' TERM INT
                |while true; do /bin/sleep 1; done
                |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        return when (val admission = IndexerExecutable.admit(executable)) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> error(admission.failure)
        }
    }

    private fun command(
        temporary: Path,
        endpoint: RuntimeEndpoint,
    ): IndexerLaunchCommand = when (
        val construction = IndexerLaunchCommand.create(
            executable(temporary),
            endpoint.root,
            endpoint,
            launchContext(temporary),
        )
    ) {
        is IndexerLaunchCommandConstruction.Created -> construction.command
        is IndexerLaunchCommandConstruction.Rejected -> error(construction.failure)
    }

    private fun failingCommand(
        temporary: Path,
        endpoint: RuntimeEndpoint,
        serviceFile: Path,
    ): IndexerLaunchCommand {
        val executable = temporary.resolve(INDEXER_FIXTURE_NAME)
        Files.writeString(
            executable,
            """#!/bin/bash
                |set -euo pipefail
                |printf '%s\n' "${'$'}{XPC_SERVICE_NAME:-}" > '${serviceFile}'
                |exit 70
                |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val admitted = when (val admission = IndexerExecutable.admit(executable)) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> error(admission.failure)
        }
        return when (val construction = IndexerLaunchCommand.create(
            admitted,
            endpoint.root,
            endpoint,
            launchContext(temporary),
        )) {
            is IndexerLaunchCommandConstruction.Created -> construction.command
            is IndexerLaunchCommandConstruction.Rejected -> error(construction.failure)
        }
    }

    private fun launchContext(temporary: Path): SidecarLaunchContext {
        val ideaHome = Files.createDirectories(temporary.resolve("idea-home")).toRealPath()
        val java = ideaHome.resolve("java")
        if (Files.notExists(java)) Files.createFile(java)
        java.toFile().setExecutable(true)
        val pair = SupportedIdeRuntimePair.admit(
            "262.9437.185",
            "262.9437.185-IJ",
        ).let { (it as SupportedIdeRuntimePairAdmission.Admitted).pair }
        val identity = IdeRuntimeIdentity.admit(
            pair,
            IdeRuntimeIdentityCandidate(
                pair.ideaBuild,
                pair.kotlinPluginBuild,
                "jbr-25.0.3+9-b508.16-aarch64",
                "sha256:${"a".repeat(64)}",
            ),
        ).let { (it as IdeRuntimeIdentityAdmission.Admitted).identity }
        val state = Files.createDirectories(temporary.resolve("sidecar-state")).toRealPath()
        val system = Files.createDirectories(state.resolve("system")).toRealPath()
        val config = Files.createDirectories(state.resolve("config")).toRealPath()
        val log = Files.createDirectories(state.resolve("log")).toRealPath()
        val plugins = Files.createDirectories(temporary.resolve("private-plugins")).toRealPath()
        return SidecarLaunchContext.admit(
            InstalledIdeRuntime(ideaHome, java.toRealPath(), identity),
            state,
            system,
            config,
            log,
            plugins,
        ).let { (it as SidecarLaunchContextAdmission.Admitted).context }
    }

    private fun awaitFile(path: Path) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (Files.notExists(path)) {
            check(System.nanoTime() < deadline) { "runtime fixture did not publish $path" }
            Thread.sleep(10)
        }
    }

    private fun processGroup(pid: Long): Long {
        val process = ProcessBuilder("/bin/ps", "-o", "pgid=", "-p", pid.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputReader().readText().trim()
        check(process.waitFor() == 0) { "could not inspect process group for $pid: $output" }
        return output.toLong()
    }

    private fun retireDirectProcess(process: ProcessHandle, serviceFile: Path) {
        val service = if (Files.exists(serviceFile)) Files.readString(serviceFile).trim() else ""
        check(service.isEmpty() || service == NO_LAUNCHD_SERVICE) {
            "direct process unexpectedly published launchd service: $service"
        }
        if (process.isAlive) {
            process.destroyForcibly()
        }
        process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS)
    }
}

private const val INDEXER_FIXTURE_NAME = "io.github.amichne.kast.indexer.KastIndexerMainKt"
private const val NO_LAUNCHD_SERVICE = "0"
