package io.github.amichne.kast.cli

import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.server.DescriptorRegistry
import io.github.amichne.kast.server.RegisteredDescriptor
import io.github.amichne.kast.server.defaultDescriptorDirectory
import io.github.amichne.kast.server.workspaceMetadataDirectory
import io.github.amichne.kast.standalone.StandaloneServerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceRuntimeManager(
    private val rpcClient: KastRpcClient,
    private val processLauncher: ProcessLauncher,
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
        ensureRuntime(options)

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
            )
        }

        return startStandaloneAndWait(
            options = options.copy(
                standaloneOptions = standaloneOptions,
                backendName = "standalone",
            ),
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

    suspend fun ensureRuntime(options: RuntimeCommandOptions): WorkspaceEnsureResult {
        val inspection = inspectWorkspace(options, pruneStaleDescriptors = true)
        selectReadyCandidate(inspection.candidates, options.backendName)?.let { selected ->
            return WorkspaceEnsureResult(
                workspaceRoot = options.workspaceRoot.toString(),
                started = false,
                selected = selected,
            )
        }

        val liveStandalone = inspection.candidates.firstOrNull { it.descriptor.backendName == "standalone" }
        if (liveStandalone != null) {
            if (!liveStandalone.reachable || liveStandalone.runtimeStatus?.state == RuntimeState.DEGRADED) {
                stopCandidate(inspection.descriptorDirectory, liveStandalone)
            } else {
                return WorkspaceEnsureResult(
                    workspaceRoot = options.workspaceRoot.toString(),
                    started = false,
                    selected = waitForReady(
                        options = options.copy(backendName = "standalone"),
                        backendName = "standalone",
                    ),
                )
            }
        }

        return startStandaloneAndWait(options)
    }

    private suspend fun startStandaloneAndWait(options: RuntimeCommandOptions): WorkspaceEnsureResult {
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

        return WorkspaceEnsureResult(
            workspaceRoot = options.workspaceRoot.toString(),
            started = true,
            logFile = logFile.toString(),
            selected = waitForReady(
                options = options.copy(
                    backendName = "standalone",
                    standaloneOptions = standaloneOptions,
                ),
                backendName = "standalone",
                launchedProcess = launched,
            ),
        )
    }

    private suspend fun waitForReady(
        options: RuntimeCommandOptions,
        backendName: String,
        launchedProcess: StartedProcess? = null,
    ): RuntimeCandidateStatus {
        val deadline = System.nanoTime() + options.waitTimeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val inspection = inspectWorkspace(options, pruneStaleDescriptors = true)
            inspection.candidates
                .firstOrNull { candidate ->
                    candidate.descriptor.backendName == backendName && candidate.ready
                }
                ?.let { return it }

            if (launchedProcess != null && !isProcessAlive(launchedProcess.pid)) {
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

        throw CliFailure(
            code = "RUNTIME_TIMEOUT",
            message = "Timed out waiting for $backendName runtime to become ready for ${options.workspaceRoot}",
        )
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
        val pidAlive = isProcessAlive(registered.descriptor.pid)
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
                rpcClient.get<RuntimeStatusResponse>(registered.descriptor, "runtime/status")
            }
        }
        val runtimeStatus = runtimeStatusResult.getOrNull()
        val capabilities = if (runtimeStatus != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    rpcClient.get<BackendCapabilities>(registered.descriptor, "capabilities")
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

internal fun RuntimeStatusResponse?.isReady(): Boolean = this != null &&
    state == RuntimeState.READY &&
    healthy &&
    active &&
    !indexing

internal fun selectReadyCandidate(
    candidates: List<RuntimeCandidateStatus>,
    backendName: String?,
): RuntimeCandidateStatus? = candidates
    .filter { candidate -> backendName == null || candidate.descriptor.backendName == backendName }
    .filter(RuntimeCandidateStatus::ready).minByOrNull(RuntimeCandidateStatus::descriptorPath)

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
