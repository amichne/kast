package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.bootstrap.SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCodec
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
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
    fun `ready progress requires endpoint reachability as well as owned ready document`(@TempDir temporary: Path) {
        val endpoint = endpoint(temporary)
        val observed = mutableListOf<SemanticRuntimeBootstrapState>()
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = launchContext(temporary),
            processStarter = RuntimeProcessStarter { command ->
                writeBootstrap(command.bootstrapState, SemanticRuntimeBootstrapState.Ready(command.bootstrapAttemptId))
                RuntimeProcessStart.Started(
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Absent }, command.bootstrapAttemptId,
                )
            },
            endpointProbe = RuntimeEndpointProbe { RuntimeEndpointReachability.Unreachable },
            progress = RuntimeStartupProgressSink(observed::add),
        )
        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.SessionEndedBeforeReady),
            demander.demand(endpoint.root, endpoint),
        )
        assertEquals(emptyList<SemanticRuntimeBootstrapState>(), observed)
    }

    @Test
    fun `reachable starting attempt waits without launching a duplicate child`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val context = launchContext(temporary)
        val runningAttempt = attempt("123e4567-e89b-42d3-a456-426614174001")
        writeBootstrap(
            context.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME),
            SemanticRuntimeBootstrapState.Starting(runningAttempt),
        )
        var probes = 0
        var starts = 0
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = context,
            processStarter = RuntimeProcessStarter {
                starts += 1
                error("reachable Starting must not launch another child")
            },
            endpointProbe = RuntimeEndpointProbe {
                probes += 1
                if (probes == 2) {
                    writeBootstrap(
                        context.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME),
                        SemanticRuntimeBootstrapState.Ready(runningAttempt),
                    )
                }
                RuntimeEndpointReachability.Reachable
            },
            bootstrapProcessAuthority = RuntimeBootstrapProcessAuthority {
                RuntimeBootstrapProcessObservation.Owned(
                    runningAttempt,
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Present },
                )
            },
        )

        assertEquals(RuntimeAdmission.Ready(endpoint), demander.demand(endpoint.root, endpoint))
        assertEquals(0, starts)
        assertEquals(2, probes)
    }

    @Test
    fun `detached exact attempt is joined after cli exit without refreshing or relaunching`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val context = launchContext(temporary)
        val runningAttempt = attempt("123e4567-e89b-42d3-a456-426614174009")
        writeBootstrap(
            context.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME),
            SemanticRuntimeBootstrapState.Starting(runningAttempt),
        )
        assertEquals(
            CacheStateTransition.Recorded,
            SidecarCacheStateFile.record(context.cacheRoot, KastCacheState.SMART),
        )
        var probes = 0
        var starts = 0
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = context,
            processStarter = RuntimeProcessStarter {
                starts += 1
                error("a detached exact attempt must be joined")
            },
            endpointProbe = RuntimeEndpointProbe {
                probes += 1
                if (probes == 2) {
                    writeBootstrap(
                        context.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME),
                        SemanticRuntimeBootstrapState.Ready(runningAttempt),
                    )
                    RuntimeEndpointReachability.Reachable
                } else {
                    RuntimeEndpointReachability.Unreachable
                }
            },
            bootstrapProcessAuthority = RuntimeBootstrapProcessAuthority {
                RuntimeBootstrapProcessObservation.Owned(
                    runningAttempt,
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Present },
                )
            },
        )

        assertEquals(RuntimeAdmission.Ready(endpoint), demander.demand(endpoint.root, endpoint))
        assertEquals(0, starts)
        assertEquals(
            CacheStateObservation.Observed(KastCacheState.SMART),
            SidecarCacheStateFile.observe(context.cacheRoot),
        )
    }

    @Test
    fun `uncorrelated existing session fails closed without adopting a stale document`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val context = launchContext(temporary)
        writeBootstrap(
            context.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME),
            SemanticRuntimeBootstrapState.Rejected(
                attempt("123e4567-e89b-42d3-a456-426614174002"),
                SemanticRuntimeBootstrapFailure.PROJECT_JVM_UNAVAILABLE,
            ),
        )
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = context,
            processStarter = RuntimeProcessStarter {
                RuntimeProcessStart.ExistingSession(
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Present },
                )
            },
            endpointProbe = RuntimeEndpointProbe { RuntimeEndpointReachability.Unreachable },
        )

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptUnavailable),
            demander.demand(endpoint.root, endpoint),
        )
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
            processStarter = RuntimeProcessStarter { command ->
                RuntimeProcessStart.Started(
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Absent },
                    command.bootstrapAttemptId,
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
    fun `sidecar bootstrap rejection survives session exit as the exact runtime failure`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val expected = SemanticRuntimeBootstrapFailure.PROJECT_JVM_UNAVAILABLE
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = launchContext(temporary),
            processStarter = RuntimeProcessStarter { command ->
                writeBootstrap(
                    command.bootstrapState,
                    SemanticRuntimeBootstrapState.Rejected(
                        command.bootstrapAttemptId,
                        expected,
                    ),
                )
                RuntimeProcessStart.Started(
                    AcceptedRuntimeStartupSession { RuntimeSessionObservation.Absent },
                    command.bootstrapAttemptId,
                )
            },
            endpointProbe = RuntimeEndpointProbe { RuntimeEndpointReachability.Unreachable },
        )

        val admission = demander.demand(endpoint.root, endpoint)

        val rejected = (admission as RuntimeAdmission.Rejected).failure as RuntimeAdmissionFailure.IntellijBootstrap
        assertEquals(expected, rejected.state.failure)
        assertEquals(
            "project-jvm-unavailable",
            (admission as RuntimeAdmission.Rejected).failure.outputReason(),
        )
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
                processStarter = RuntimeProcessStarter { command ->
                    RuntimeProcessStart.Started(
                        AcceptedRuntimeStartupSession { observation },
                        command.bootstrapAttemptId,
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
            RuntimeProcessStart.ExistingSession(
                session,
            ),
            session.start(command(temporary, endpoint)),
        )
        assertEquals(listOf("list"), invocations)
    }

    @Test
    fun `launchd starts from an empty environment before applying the JBR allowlist`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        var submission: List<String>? = null
        val command = command(temporary, endpoint)
        val session = MacOsRuntimeProcessSession.from(
            endpoint,
            LaunchctlInvoker { arguments, _ ->
                when (arguments[1]) {
                    "list" -> LaunchctlInvocation.Absent
                    "submit" -> {
                        submission = arguments
                        LaunchctlInvocation.Completed
                    }
                    else -> error("unexpected launchctl operation")
                }
            },
        )

        assertInstanceOf(RuntimeProcessStart.Started::class.java, session.start(command))
        val environmentIndex = submission.orEmpty().indexOf("/usr/bin/env")
        val standardErrorIndex = submission.orEmpty().indexOf("-e")
        assertTrue(environmentIndex >= 0)
        assertEquals("-i", submission.orEmpty()[environmentIndex + 1])
        assertTrue(submission.orEmpty()[environmentIndex + 2].startsWith("JAVA_HOME="))
        assertEquals(command.startupLog.toString(), submission.orEmpty()[standardErrorIndex + 1])
    }

    @Test
    fun `JBR environment rejection survives to the runtime admission boundary`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val failure = RuntimeProcessStartFailure.IdeaJbrUnavailable
        val demander = ExactRootProcessRuntimeDemander(
            executable = executable(temporary),
            launchContext = launchContext(temporary),
            processStarter = RuntimeProcessStarter { RuntimeProcessStart.Rejected(failure) },
            endpointProbe = RuntimeEndpointProbe { RuntimeEndpointReachability.Unreachable },
        )

        val admission = demander.demand(endpoint.root, endpoint)

        assertEquals(
            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ProcessStartFailed(failure)),
            admission,
        )
        assertEquals(
            "idea-jbr-unavailable",
            (admission as RuntimeAdmission.Rejected).failure.outputReason(),
        )
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
            RuntimeProcessStart.ExistingSession(
                session,
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

        assertTrue(rejected.start(command) is RuntimeProcessStart.Rejected)
        assertEquals(RuntimeProcessStart.Interrupted, interrupted.start(command))
    }

    @Test
    fun `launchd label remains owned until its exact session is retired`(
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
        val accepted = assertInstanceOf(RuntimeProcessStart.Started::class.java, start)
        assertEquals(command(temporary, endpoint).bootstrapAttemptId, accepted.attemptId)
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
    fun `direct launch survives the initiating terminal hangup`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val pidFile = endpoint.socketPath.resolveSibling("${endpoint.socketPath.fileName}.pid")
        val serviceFile = endpoint.socketPath.resolveSibling(
            "${endpoint.socketPath.fileName}.service",
        )

        val start = JdkRuntimeProcessStarter.start(command(temporary, endpoint))
        val accepted = assertInstanceOf(RuntimeProcessStart.Started::class.java, start)
        awaitFile(pidFile)
        awaitFile(serviceFile)
        val process = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()

        try {
            assertNotEquals(
                processGroup(ProcessHandle.current().pid()),
                processGroup(process.pid()),
                "direct runtime must leave the initiating caller's process group",
            )
            signalHangup(process.pid())
            Thread.sleep(250)
            assertTrue(process.isAlive, "direct runtime must outlive the initiating terminal")
            assertEquals(RuntimeSessionObservation.Present, accepted.session.observe())
            assertEquals("", Files.readString(serviceFile).trim())
        } finally {
            retireDirectProcess(process, serviceFile)
        }
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
            start is RuntimeProcessStart.Started
        ) { "runtime process did not start: $start" }
        awaitFile(pidFile)
        awaitFile(serviceFile)
        val process = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()

        try {
            assertEquals("", Files.readString(serviceFile).trim())
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
    fun `non terminal status from failed child still retires its launchd session`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val serviceFile = endpoint.socketPath.resolveSibling("${endpoint.socketPath.fileName}.failed-service")
        val session = MacOsRuntimeProcessSession.from(endpoint)
        val command = failingCommand(temporary, endpoint, serviceFile)
        val start = session.start(command)
        check(
            start is RuntimeProcessStart.Started
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
            assertTrue(Files.readString(command.startupLog).contains("fixture-startup-rejected"))
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
            attempt(),
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
                |printf '%s\n' 'fixture-startup-rejected' >&2
                |exit 64
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
            attempt(),
        )) {
            is IndexerLaunchCommandConstruction.Created -> construction.command
            is IndexerLaunchCommandConstruction.Rejected -> error(construction.failure)
        }
    }

    private fun launchContext(temporary: Path): SidecarLaunchContext {
        val ideaHome = Files.createDirectories(temporary.resolve("idea-home")).toRealPath()
        val java = Files.createDirectories(
            ideaHome.resolve("jbr/Contents/Home/bin"),
        ).resolve("java")
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

    private fun attempt(
        raw: String = "123e4567-e89b-42d3-a456-426614174000",
    ): SemanticRuntimeBootstrapAttemptId = when (
        val admission = SemanticRuntimeBootstrapAttemptId.admit(raw)
    ) {
        is Refinement.Refined -> admission.value
        is Refinement.Rejected -> error(admission.failure)
    }

    private fun writeBootstrap(path: Path, state: SemanticRuntimeBootstrapState) {
        Files.writeString(path, SemanticRuntimeBootstrapCodec.encode(state))
    }

    private fun processGroup(pid: Long): Long {
        val process = ProcessBuilder("/bin/ps", "-o", "pgid=", "-p", pid.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputReader().readText().trim()
        check(process.waitFor() == 0) { "could not inspect process group for $pid: $output" }
        return output.toLong()
    }

    private fun signalHangup(pid: Long) {
        val process = ProcessBuilder("/bin/kill", "-HUP", pid.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputReader().readText().trim()
        check(process.waitFor() == 0) { "could not hang up process $pid: $output" }
    }

    private fun retireDirectProcess(process: ProcessHandle, serviceFile: Path) {
        val service = if (Files.exists(serviceFile)) Files.readString(serviceFile).trim() else ""
        check(service.isEmpty()) {
            "direct process unexpectedly published launchd service: $service"
        }
        if (process.isAlive) {
            process.destroyForcibly()
        }
        process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS)
    }
}

private const val INDEXER_FIXTURE_NAME = "io.github.amichne.kast.indexer.KastIndexerMainKt"
