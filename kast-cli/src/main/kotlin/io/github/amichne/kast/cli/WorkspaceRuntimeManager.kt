package io.github.amichne.kast.cli

import io.github.amichne.kast.api.DescriptorRegistry
import io.github.amichne.kast.api.RegisteredDescriptor
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.StandaloneServerOptions
import io.github.amichne.kast.api.defaultDescriptorDirectory
import io.github.amichne.kast.api.workspaceMetadataDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceRuntimeManager(
    private val rpcClient: RuntimeRpcClient,
    private val processLauncher: ProcessLauncher,
    private val processLivenessChecker: (Long) -> Boolean = ::isProcessAlive,
) {
    suspend fun workspaceStatus(options: RuntimeCommandOptions): WorkspaceStatusResult {
        val inspection = inspectWorkspace(options, pruneStaleDescriptors = false)
        return WorkspaceStatusResult(
            workspaceRoot = options.workspaceRoot.toString(),
            descriptorDirectory = inspection.descriptorDirectory.toString(),
            selected = inspection.selected,
            candidates = inspection.candidates,
        )
    }

    suspend fun workspaceEnsure(options: RuntimeCommandOptions): WorkspaceEnsureResult =
        ensureRuntime(
            options = options,
            requireReady = !options.acceptIndexing,
            purpose = EnsureRuntimePurpose.WORKSPACE_ENSURE,
        )

    suspend fun daemonStart(options: RuntimeCommandOptions): WorkspaceEnsureResult {
        val standaloneOptions = options.requireStandaloneBackend()
        val readyRuntime = inspectWorkspace(options, pruneStaleDescriptors = true)
            .candidates
            .firstOrNull { candidate ->
                candidate.descriptor.backendName == "standalone" && candidate.ready
            }
        if (readyRuntime != null) {
            return WorkspaceEnsureResult(
                workspaceRoot = options.workspaceRoot.toString(),
                started = false,
                selected = readyRuntime,
                note = deprecatedDaemonStartNote(
                    workspaceRoot = options.workspaceRoot,
                    selected = readyRuntime,
                ),
            )
        }

        return startStandaloneAndWait(
            options = options.copy(
                standaloneOptions = standaloneOptions,
                backendName = "standalone",
            ),
            requireReady = true,
            purpose = EnsureRuntimePurpose.DAEMON_START,
        )
    }

    suspend fun daemonStop(options: RuntimeCommandOptions): DaemonStopResult {
        val inspection = inspectWorkspace(
            options = options.copy(backendName = "standalone"),
            pruneStaleDescriptors = true,
        )
        val candidate = inspection.candidates.firstOrNull { it.descriptor.backendName == "standalone" }
            ?: return DaemonStopResult(
                workspaceRoot = options.workspaceRoot.toString(),
                stopped = false,
            )

        return stopCandidate(
            descriptorDirectory = inspection.descriptorDirectory,
            candidate = candidate,
        )
    }

    suspend fun ensureRuntime(
        options: RuntimeCommandOptions,
        requireReady: Boolean = false,
        purpose: EnsureRuntimePurpose = EnsureRuntimePurpose.COMMAND,
    ): WorkspaceEnsureResult {
        val inspection = inspectWorkspace(options, pruneStaleDescriptors = true)
        selectServableCandidate(
            candidates = inspection.candidates,
            backendName = options.backendName,
            acceptIndexing = !requireReady,
        )?.let { selected ->
            return WorkspaceEnsureResult(
                workspaceRoot = options.workspaceRoot.toString(),
                started = false,
                selected = selected,
            )
        }

        val liveStandalone = inspection.candidates.firstOrNull { it.descriptor.backendName == "standalone" }
        if (liveStandalone != null) {
            if (!liveStandalone.reachable || liveStandalone.runtimeStatus?.state == RuntimeState.DEGRADED) {
                if (options.noAutoStart) {
                    throw noAutoStartFailure(options, liveStandalone)
                }
                stopCandidate(inspection.descriptorDirectory, liveStandalone)
            } else {
                return WorkspaceEnsureResult(
                    workspaceRoot = options.workspaceRoot.toString(),
                    started = false,
                    selected = waitForServable(
                        options = options.copy(backendName = "standalone"),
                        backendName = "standalone",
                        acceptIndexing = !requireReady,
                    ),
                )
            }
        }

        if (options.noAutoStart) {
            throw noAutoStartFailure(options)
        }

        return startStandaloneAndWait(
            options = options,
            requireReady = requireReady,
            purpose = purpose,
        )
    }

    private suspend fun startStandaloneAndWait(
        options: RuntimeCommandOptions,
        requireReady: Boolean,
        purpose: EnsureRuntimePurpose,
    ): WorkspaceEnsureResult {
        val standaloneOptions = options.requireStandaloneBackend()
        val logFile = workspaceMetadataDirectory(options.workspaceRoot)
            .resolve("logs")
            .createDirectories()
            .resolve("standalone-daemon.log")
        val launched = processLauncher.startDetached(
            mainClassName = "io.github.amichne.kast.standalone.StandaloneMainKt",
            workingDirectory = options.workspaceRoot,
            logFile = logFile,
            arguments = standaloneOptions.toCliArguments(),
        )

        val selected = waitForServable(
            options = options.copy(
                backendName = "standalone",
                standaloneOptions = standaloneOptions,
            ),
            backendName = "standalone",
            acceptIndexing = !requireReady,
            launchedProcess = launched,
        )
        return WorkspaceEnsureResult(
            workspaceRoot = options.workspaceRoot.toString(),
            started = true,
            logFile = logFile.toString(),
            selected = selected,
            note = when (purpose) {
                EnsureRuntimePurpose.COMMAND -> autoStartedDaemonNote(
                    workspaceRoot = options.workspaceRoot,
                    selected = selected,
                )
                EnsureRuntimePurpose.WORKSPACE_ENSURE -> null
                EnsureRuntimePurpose.DAEMON_START -> deprecatedDaemonStartNote(
                    workspaceRoot = options.workspaceRoot,
                    selected = selected,
                    logFile = logFile,
                    started = true,
                )
            },
        )
    }

    private suspend fun waitForServable(
        options: RuntimeCommandOptions,
        backendName: String,
        acceptIndexing: Boolean,
        launchedProcess: StartedProcess? = null,
    ): RuntimeCandidateStatus {
        val deadline = System.nanoTime() + options.waitTimeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val inspection = inspectWorkspace(options, pruneStaleDescriptors = true)
            selectServableCandidate(
                candidates = inspection.candidates,
                backendName = backendName,
                acceptIndexing = acceptIndexing,
            )?.let { return it }

            if (launchedProcess != null && !processLivenessChecker(launchedProcess.pid)) {
                throw CliFailure(
                    code = "DAEMON_START_FAILED",
                    message = "The standalone daemon exited before it became ready",
                    details = mapOf(
                        "logFile" to launchedProcess.logFile.toString(),
                        "pid" to launchedProcess.pid.toString(),
                    ),
                )
            }

            delay(250.milliseconds)
        }

        val targetState = if (acceptIndexing) "servable" else "ready"
        throw CliFailure(
            code = "RUNTIME_TIMEOUT",
            message = "Timed out waiting for $backendName runtime to become $targetState for ${options.workspaceRoot}",
        )
    }

    private fun noAutoStartFailure(
        options: RuntimeCommandOptions,
        existingCandidate: RuntimeCandidateStatus? = null,
    ): CliFailure = CliFailure(
        code = "DAEMON_NOT_RUNNING",
        message = existingCandidate?.let { candidate ->
            "No servable standalone daemon is available for ${options.workspaceRoot}; the existing daemon is ${candidate.currentStateLabel()}. Rerun without --no-auto-start or start one with `kast workspace ensure`."
        } ?: "No standalone daemon is registered for ${options.workspaceRoot}. Rerun without --no-auto-start or start one with `kast workspace ensure`.",
    )

    private fun autoStartedDaemonNote(
        workspaceRoot: Path,
        selected: RuntimeCandidateStatus,
    ): String = "kast: started daemon for $workspaceRoot (state: ${selected.currentStateLabel()})"

    private fun deprecatedDaemonStartNote(
        workspaceRoot: Path,
        selected: RuntimeCandidateStatus,
        logFile: Path? = null,
        started: Boolean = false,
    ): String = buildString {
        append("daemon: ")
        append(if (started) "started" else "using")
        append(' ')
        append(selected.describeDaemon())
        if (started && logFile != null) {
            append(" (log: $logFile)")
        }
        append(" — deprecated: use `kast workspace ensure --workspace-root=$workspaceRoot` or let analysis commands auto-start the daemon.")
    }

    internal enum class EnsureRuntimePurpose {
        COMMAND,
        WORKSPACE_ENSURE,
        DAEMON_START,
    }

    private suspend fun inspectWorkspace(
        options: RuntimeCommandOptions,
        pruneStaleDescriptors: Boolean,
    ): WorkspaceInspection {
        val descriptorDirectory = defaultDescriptorDirectory(options.workspaceRoot)
        val registry = DescriptorRegistry(descriptorDirectory)
        val registeredDescriptors = registry.findByWorkspaceRoot(options.workspaceRoot)
        val candidates = registeredDescriptors.map { registered ->
            inspectDescriptor(registry, registered, pruneStaleDescriptors)
        }.filter { candidate -> candidate.descriptor.backendName == "standalone" }

        return WorkspaceInspection(
            descriptorDirectory = descriptorDirectory,
            candidates = candidates.sortedWith(
                compareByDescending(RuntimeCandidateStatus::ready)
                    .thenBy(RuntimeCandidateStatus::descriptorPath),
            ),
            selected = selectStatusCandidate(candidates, options.backendName),
        )
    }

    private suspend fun inspectDescriptor(
        registry: DescriptorRegistry,
        registered: RegisteredDescriptor,
        pruneStaleDescriptors: Boolean,
    ): RuntimeCandidateStatus {
        val pidAlive = processLivenessChecker(registered.descriptor.pid)
        if (!pidAlive && pruneStaleDescriptors) {
            registry.delete(registered.path)
        }

        if (!pidAlive) {
            return RuntimeCandidateStatus(
                descriptorPath = registered.path.toString(),
                descriptor = registered.descriptor,
                pidAlive = false,
                reachable = false,
                ready = false,
                errorMessage = "Process ${registered.descriptor.pid} is not alive",
            )
        }

        val runtimeStatusResult = withContext(Dispatchers.IO) {
            runCatching {
                rpcClient.runtimeStatus(registered.descriptor)
            }
        }
        val runtimeStatus = runtimeStatusResult.getOrNull()
        val capabilities = if (runtimeStatus != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    rpcClient.capabilities(registered.descriptor)
                }.getOrNull()
            }
        } else {
            null
        }

        return RuntimeCandidateStatus(
            descriptorPath = registered.path.toString(),
            descriptor = registered.descriptor,
            pidAlive = true,
            reachable = runtimeStatus != null,
            ready = runtimeStatus.isReady(),
            runtimeStatus = runtimeStatus,
            capabilities = capabilities,
            errorMessage = runtimeStatusResult.exceptionOrNull()?.message,
        )
    }

    private suspend fun stopCandidate(
        descriptorDirectory: Path,
        candidate: RuntimeCandidateStatus,
    ): DaemonStopResult {
        val processHandle = ProcessHandle.of(candidate.descriptor.pid)
            .takeIf { it.isPresent }
            ?.get()
        val forced = if (processHandle?.isAlive == true) {
            processHandle.destroy()
            repeat(20) {
                if (!processHandle.isAlive) {
                    return@repeat
                }
                delay(250.milliseconds)
            }
            if (processHandle.isAlive) {
                processHandle.destroyForcibly()
                true
            } else {
                false
            }
        } else {
            false
        }

        DescriptorRegistry(descriptorDirectory).delete(Path.of(candidate.descriptorPath))
        return DaemonStopResult(
            workspaceRoot = candidate.descriptor.workspaceRoot,
            stopped = true,
            descriptorPath = candidate.descriptorPath,
            pid = candidate.descriptor.pid,
            forced = forced,
        )
    }
}

internal data class WorkspaceInspection(
    val descriptorDirectory: Path,
    val candidates: List<RuntimeCandidateStatus>,
    val selected: RuntimeCandidateStatus?,
)

internal fun RuntimeStatusResponse?.isServable(): Boolean = this != null &&
    (state == RuntimeState.READY || state == RuntimeState.INDEXING) &&
    healthy &&
    active

internal fun RuntimeStatusResponse?.isReady(): Boolean = this != null &&
    state == RuntimeState.READY &&
    healthy &&
    active &&
    !indexing

internal fun selectServableCandidate(
    candidates: List<RuntimeCandidateStatus>,
    backendName: String?,
    acceptIndexing: Boolean,
): RuntimeCandidateStatus? = candidates
    .filter { candidate -> backendName == null || candidate.descriptor.backendName == backendName }
    .filter { candidate ->
        if (acceptIndexing) {
            candidate.runtimeStatus.isServable()
        } else {
            candidate.ready
        }
    }
    .minByOrNull(RuntimeCandidateStatus::descriptorPath)

internal fun selectStatusCandidate(
    candidates: List<RuntimeCandidateStatus>,
    backendName: String?,
): RuntimeCandidateStatus? = candidates
    .filter { candidate -> backendName == null || candidate.descriptor.backendName == backendName }
    .sortedWith(
        compareByDescending(RuntimeCandidateStatus::ready)
            .thenBy(RuntimeCandidateStatus::descriptorPath),
    )
    .firstOrNull()

internal fun RuntimeCandidateStatus.currentStateLabel(): String = when {
    runtimeStatus?.state == RuntimeState.INDEXING || runtimeStatus?.indexing == true -> "INDEXING, enrichment in progress"
    runtimeStatus != null -> runtimeStatus.state.name
    reachable -> "STARTING"
    pidAlive -> "UNREACHABLE"
    else -> "STOPPED"
}

private fun isProcessAlive(pid: Long): Boolean = ProcessHandle.of(pid)
    .takeIf { it.isPresent }
    ?.get()
    ?.isAlive
    ?: false

private fun RuntimeCommandOptions.requireStandaloneBackend(): StandaloneServerOptions {
    return standaloneOptions ?: StandaloneServerOptions.fromValues(
        mapOf(
            "workspace-root" to workspaceRoot.toString(),
            "request-timeout-ms" to "30000",
            "max-results" to "500",
            "max-concurrent-requests" to "4",
        ),
    )
}
