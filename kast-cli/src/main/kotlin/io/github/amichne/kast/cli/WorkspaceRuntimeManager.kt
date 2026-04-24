package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.DescriptorRegistry
import io.github.amichne.kast.api.client.RegisteredDescriptor
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.client.defaultDescriptorDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceRuntimeManager(
    private val rpcClient: RuntimeRpcClient,
    private val processLivenessChecker: (Long) -> Boolean = ::isProcessAlive,
    private val envLookup: (String) -> String? = System::getenv,
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
        )

    suspend fun workspaceStop(options: RuntimeCommandOptions): DaemonStopResult {
        val inspection = inspectWorkspace(
            options = options,
            pruneStaleDescriptors = true,
        )
        val backendFilter = options.backendName
        val candidate = if (backendFilter != null) {
            inspection.candidates.firstOrNull { it.descriptor.backendName == backendFilter }
        } else {
            inspection.candidates.firstOrNull()
        }
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

        if (options.backendName == "intellij") {
            throw CliFailure(
                code = "INTELLIJ_NOT_RUNNING",
                message = "No IntelliJ backend is available for ${options.workspaceRoot}. " +
                    "Open the project in IntelliJ IDEA with the Kast plugin installed.",
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
                    selected = waitForServable(
                        options = options.copy(backendName = "standalone"),
                        backendName = "standalone",
                        acceptIndexing = !requireReady,
                    ),
                )
            }
        }

        throw CliFailure(
            code = "NO_BACKEND_AVAILABLE",
            message = "No backend is running for ${options.workspaceRoot}. " +
                "Start with: kast-standalone --workspace-root=${options.workspaceRoot}",
        )
    }

    private suspend fun waitForServable(
        options: RuntimeCommandOptions,
        backendName: String,
        acceptIndexing: Boolean,
    ): RuntimeCandidateStatus {
        val deadline = System.nanoTime() + options.waitTimeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val inspection = inspectWorkspace(options, pruneStaleDescriptors = true)
            selectServableCandidate(
                candidates = inspection.candidates,
                backendName = backendName,
                acceptIndexing = acceptIndexing,
            )?.let { return it }

            delay(250.milliseconds)
        }

        val targetState = if (acceptIndexing) "servable" else "ready"
        throw CliFailure(
            code = "RUNTIME_TIMEOUT",
            message = "Timed out waiting for $backendName runtime to become $targetState for ${options.workspaceRoot}",
        )
    }

    private suspend fun inspectWorkspace(
        options: RuntimeCommandOptions,
        pruneStaleDescriptors: Boolean,
    ): WorkspaceInspection {
        val descriptorDirectory = defaultDescriptorDirectory(envLookup)
        val registry = DescriptorRegistry(descriptorDirectory.resolve("daemons.json"))
        val registeredDescriptors = registry.findByWorkspaceRoot(options.workspaceRoot)
        val candidates = registeredDescriptors.map { registered ->
            inspectDescriptor(registry, registered, pruneStaleDescriptors)
        }

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
            registry.delete(registered.descriptor)
        }

        if (!pidAlive) {
            return RuntimeCandidateStatus(
                descriptorPath = registered.id,
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
            descriptorPath = registered.id,
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

        DescriptorRegistry(descriptorDirectory.resolve("daemons.json")).delete(candidate.descriptor)
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
    .sortedWith(
        // Prefer intellij over standalone when both are available (lighter weight).
        compareByDescending<RuntimeCandidateStatus> { it.descriptor.backendName == "intellij" }
            .thenBy(RuntimeCandidateStatus::descriptorPath),
    )
    .firstOrNull()

internal fun selectStatusCandidate(
    candidates: List<RuntimeCandidateStatus>,
    backendName: String?,
): RuntimeCandidateStatus? = candidates
    .filter { candidate -> backendName == null || candidate.descriptor.backendName == backendName }
    .sortedWith(
        compareByDescending(RuntimeCandidateStatus::ready)
            .thenByDescending { it.descriptor.backendName == "intellij" }
            .thenBy(RuntimeCandidateStatus::descriptorPath),
    )
    .firstOrNull()

internal fun RuntimeCandidateStatus.currentStateLabel(): String = when {
    runtimeStatus?.state == RuntimeState.INDEXING || runtimeStatus?.indexing == true -> "INDEXING, enrichment in progress"
    runtimeStatus != null -> runtimeStatus.state.name
    reachable -> RuntimeState.STARTING.name
    pidAlive -> "UNREACHABLE"
    else -> "STOPPED"
}

private fun isProcessAlive(pid: Long): Boolean = ProcessHandle.of(pid)
    .takeIf { it.isPresent }
    ?.get()
    ?.isAlive
    ?: false
