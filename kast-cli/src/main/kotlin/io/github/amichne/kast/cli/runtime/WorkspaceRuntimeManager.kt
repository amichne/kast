package io.github.amichne.kast.cli.runtime

import io.github.amichne.kast.api.client.DescriptorRegistry
import io.github.amichne.kast.api.client.RegisteredDescriptor
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.client.defaultDescriptorDirectory
import io.github.amichne.kast.cli.CliFailure
import io.github.amichne.kast.cli.RuntimeRpcClient
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
    suspend fun workspaceStatus(request: RuntimeLifecycleRequest): WorkspaceStatusResult {
        val inspection = inspectWorkspace(request.selection, pruneStaleDescriptors = false)
        return WorkspaceStatusResult(
            workspaceRoot = request.selection.workspaceRoot.toString(),
            descriptorDirectory = inspection.descriptorDirectory.toString(),
            selected = inspection.selected,
            candidates = inspection.candidates,
        )
    }

    suspend fun workspaceEnsure(request: RuntimeLifecycleRequest): WorkspaceEnsureResult =
        ensureRuntime(
            request = request,
            requireReady = !request.acceptIndexing,
        )

    suspend fun workspaceStop(request: RuntimeLifecycleRequest): DaemonStopResult {
        val inspection = inspectWorkspace(
            selection = request.selection,
            pruneStaleDescriptors = true,
        )
        val backendFilter = request.selection.backendName
        val candidate = if (backendFilter != null) {
            inspection.candidates.firstOrNull { it.descriptor.backendName == backendFilter }
        } else {
            inspection.candidates.firstOrNull()
        }
            ?: return DaemonStopResult(
                workspaceRoot = request.selection.workspaceRoot.toString(),
                stopped = false,
            )

        return stopCandidate(
            descriptorDirectory = inspection.descriptorDirectory,
            candidate = candidate,
        )
    }

    suspend fun ensureRuntime(
        request: RuntimeLifecycleRequest,
        requireReady: Boolean = false,
    ): WorkspaceEnsureResult {
        val inspection = inspectWorkspace(request.selection, pruneStaleDescriptors = true)
        selectServableCandidate(
            candidates = inspection.candidates,
            backendName = request.selection.backendName,
            acceptIndexing = !requireReady,
        )?.let { selected ->
            return WorkspaceEnsureResult(
                workspaceRoot = request.selection.workspaceRoot.toString(),
                started = false,
                selected = selected,
            )
        }

        if (request.selection.backendName == "intellij") {
            throw CliFailure(
                code = "INTELLIJ_NOT_RUNNING",
                message = "No IntelliJ backend is available for ${request.selection.workspaceRoot}. " +
                    "Open the project in IntelliJ IDEA with the Kast plugin installed.",
            )
        }

        val liveStandalone = inspection.candidates.firstOrNull { it.descriptor.backendName == "standalone" }
        if (liveStandalone != null) {
            if (!liveStandalone.reachable || liveStandalone.runtimeStatus?.state == RuntimeState.DEGRADED) {
                stopCandidate(inspection.descriptorDirectory, liveStandalone)
            } else {
                return WorkspaceEnsureResult(
                    workspaceRoot = request.selection.workspaceRoot.toString(),
                    started = false,
                    selected = waitForServable(
                        selection = request.selection.copy(backendName = "standalone"),
                        backendName = "standalone",
                        acceptIndexing = !requireReady,
                    ),
                )
            }
        }

        throw CliFailure(
            code = "NO_BACKEND_AVAILABLE",
            message = "No backend is running for ${request.selection.workspaceRoot}. " +
                "Start with: kast-standalone --workspace-root=${request.selection.workspaceRoot}",
        )
    }

    private suspend fun waitForServable(
        selection: RuntimeSelection,
        backendName: String,
        acceptIndexing: Boolean,
    ): RuntimeCandidateStatus {
        val deadline = System.nanoTime() + selection.waitTimeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val inspection = inspectWorkspace(selection, pruneStaleDescriptors = true)
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
            message = "Timed out waiting for $backendName runtime to become $targetState for ${selection.workspaceRoot}",
        )
    }

    private suspend fun inspectWorkspace(
        selection: RuntimeSelection,
        pruneStaleDescriptors: Boolean,
    ): WorkspaceInspection {
        val descriptorDirectory = defaultDescriptorDirectory(envLookup)
        val registry = DescriptorRegistry(descriptorDirectory.resolve("daemons.json"))
        val registeredDescriptors = registry.findByWorkspaceRoot(selection.workspaceRoot)
        val candidates = registeredDescriptors.map { registered ->
            inspectDescriptor(registry, registered, pruneStaleDescriptors)
        }

        return WorkspaceInspection(
            descriptorDirectory = descriptorDirectory,
            candidates = candidates.sortedWith(
                compareByDescending(RuntimeCandidateStatus::ready)
                    .thenBy(RuntimeCandidateStatus::descriptorPath),
            ),
            selected = selectStatusCandidate(candidates, selection.backendName),
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
