package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.DescriptorRegistry
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.RegisteredDescriptor
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.cli.options.BackendName
import io.github.amichne.kast.cli.options.RuntimeCommandOptions
import io.github.amichne.kast.cli.results.DaemonStopResult
import io.github.amichne.kast.cli.results.WorkspaceEnsureResult
import io.github.amichne.kast.cli.results.WorkspaceStatusResult
import io.github.amichne.kast.cli.tty.CliFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal class WorkspaceRuntimeManager(
    private val rpcClient: RuntimeRpcClient,
    private val processLivenessChecker: (Long) -> Boolean = ::isProcessAlive,
    private val descriptorDirectory: (RuntimeCommandOptions) -> Path = ::configuredDescriptorDirectory,
    private val configLoader: (Path) -> KastConfig = KastConfig::load,
    private val standaloneDaemonStarter: suspend (RuntimeCommandOptions, Path) -> StandaloneDaemonLaunch = ::startStandaloneDaemon,
) {
    private companion object {
        fun configuredDescriptorDirectory(options: RuntimeCommandOptions): Path =
            KastConfig.load(options.workspaceRoot.toJavaPath()).paths.descriptorDir.toPath()
    }

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
        val backendFilter = options.backendName?.canonicalName
        val candidate = if (backendFilter != null) {
            inspection.candidates.firstOrNull { it.descriptor.backendName == backendFilter }
        } else {
            inspection.candidates.firstOrNull()
        } ?: return DaemonStopResult(
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
                descriptorDirectory = inspection.descriptorDirectory.toString(),
                started = false,
                selected = selected,
            )
        }

        if (options.backendName == BackendName.INTELLIJ) {
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
                    descriptorDirectory = inspection.descriptorDirectory.toString(),
                    started = false,
                    selected = waitForServable(
                        options = options.copy(backendName = BackendName.STANDALONE),
                        backendName = BackendName.STANDALONE,
                        acceptIndexing = !requireReady,
                    ),
                )
            }
        }

        if (!options.noAutoStart) {
            val config = configLoader(options.workspaceRoot.toJavaPath())
            val runtimeLibsDir = config.backends.standalone.runtimeLibsDir.value.orNull
                ?.takeIf(String::isNotBlank)
                ?.let { configured -> Path.of(configured).toAbsolutePath().normalize() }
            if (runtimeLibsDir != null && Files.isDirectory(runtimeLibsDir)) {
                val launch = standaloneDaemonStarter(
                    options.copy(backendName = BackendName.STANDALONE),
                    runtimeLibsDir,
                )
                return WorkspaceEnsureResult(
                    workspaceRoot = options.workspaceRoot.toString(),
                    descriptorDirectory = inspection.descriptorDirectory.toString(),
                    started = true,
                    logFile = launch.logFile?.toString(),
                    selected = waitForServable(
                        options = options.copy(backendName = BackendName.STANDALONE),
                        backendName = BackendName.STANDALONE,
                        acceptIndexing = !requireReady,
                    ),
                )
            }
        }

        throw CliFailure(
            code = "NO_BACKEND_AVAILABLE",
            message = "No backend is running for ${options.workspaceRoot}. " +
                      "Start with: kast daemon start --workspace-root=${options.workspaceRoot}",
        )
    }

    private suspend fun waitForServable(
        options: RuntimeCommandOptions,
        backendName: BackendName,
        acceptIndexing: Boolean,
    ): RuntimeCandidateStatus {
        val deadline = System.nanoTime() + options.waitTimeoutMillis.value * 1_000_000
        while (System.nanoTime() < deadline) {
            val inspection = inspectWorkspace(options, pruneStaleDescriptors = true)
            selectServableCandidate(
                candidates = inspection.candidates,
                backendName = backendName,
                acceptIndexing = acceptIndexing,
            )?.let { return it }

            delay(250)
        }

        val targetState = if (acceptIndexing) "servable" else "ready"
        throw CliFailure(
            code = "RUNTIME_TIMEOUT",
            message = "Timed out waiting for ${backendName.canonicalName} runtime to become $targetState for ${options.workspaceRoot}",
        )
    }

    private suspend fun inspectWorkspace(
        options: RuntimeCommandOptions,
        pruneStaleDescriptors: Boolean,
    ): WorkspaceInspection {
        val descriptorDirectory = descriptorDirectory(options)
        val registry = DescriptorRegistry(descriptorDirectory.resolve("daemons.json"))
        val registeredDescriptors = registry.findByWorkspaceRoot(options.workspaceRoot.toJavaPath())
        val candidates = registeredDescriptors.map { registered ->
            inspectDescriptor(registry, registered, pruneStaleDescriptors)
        }

        return WorkspaceInspection(
            descriptorDirectory = descriptorDirectory,
            candidates = candidates.sortedWith(
                compareByDescending(RuntimeCandidateStatus::ready)
                    .thenBy(RuntimeCandidateStatus::descriptorPath),
            ),
            selected = selectStatusCandidate(candidates, options.backendName?.canonicalName),
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
        val forced = if (candidate.descriptor.backendName == "intellij") {
            // Never kill the IntelliJ process — only deregister the descriptor so the
            // plugin can re-register on its next heartbeat or project open.
            false
        } else {
            val processHandle = ProcessHandle.of(candidate.descriptor.pid)
                .takeIf { it.isPresent }
                ?.get()
            if (processHandle?.isAlive == true) {
                processHandle.destroy()
                repeat(20) {
                    if (!processHandle.isAlive) {
                        return@repeat
                    }
                    delay(250)
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

internal data class StandaloneDaemonLaunch(
    val logFile: Path? = null,
)

internal data class WorkspaceInspection(
    val descriptorDirectory: Path,
    val candidates: List<RuntimeCandidateStatus>,
    val selected: RuntimeCandidateStatus?,
)

internal fun RuntimeStatusResponse?.isServable(): Boolean =
    this != null &&
    (state == RuntimeState.READY || state == RuntimeState.INDEXING) &&
    healthy &&
    active

internal fun RuntimeStatusResponse?.isReady(): Boolean =
    this != null &&
    state == RuntimeState.READY &&
    healthy &&
    active &&
    !indexing

internal fun selectServableCandidate(
    candidates: List<RuntimeCandidateStatus>,
    backendName: BackendName?,
    acceptIndexing: Boolean,
): RuntimeCandidateStatus? = candidates
    .filter { candidate -> backendName == null || candidate.descriptor.backendName == backendName.canonicalName }
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

private fun isProcessAlive(pid: Long): Boolean =
    ProcessHandle.of(pid)
        .takeIf { it.isPresent }
        ?.get()
        ?.isAlive
    ?: false

private suspend fun startStandaloneDaemon(
    options: RuntimeCommandOptions,
    runtimeLibsDir: Path,
): StandaloneDaemonLaunch {
    val workspaceRoot = options.workspaceRoot.toJavaPath()
    val kastBinary = resolveKastBinary(workspaceRoot)
                     ?: throw CliFailure(
                         code = "DAEMON_START_ERROR",
                         message = "Cannot locate the kast launcher needed to auto-start the standalone backend for $workspaceRoot.",
                     )
    val logFile = daemonLogFile(KastConfig.load(workspaceRoot), workspaceRoot)
    Files.createDirectories(checkNotNull(logFile.parent))
    val command = buildList {
        add(kastBinary.toString())
        add("daemon")
        add("start")
        addAll(
            options.standaloneOptions?.toCliArguments()
            ?: listOf("--workspace-root=$workspaceRoot"),
        )
        add("--runtime-libs-dir=$runtimeLibsDir")
    }
    runCatching {
        ProcessBuilder(command)
            .directory(workspaceRoot.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
    }.getOrElse { error ->
        throw CliFailure(
            code = "DAEMON_START_ERROR",
            message = "Failed to auto-start the standalone backend for $workspaceRoot: ${error.message ?: error::class.simpleName}",
        )
    }
    return StandaloneDaemonLaunch(logFile = logFile)
}

private fun resolveKastBinary(workspaceRoot: Path): Path? {
    val configBinary = KastConfig.load(workspaceRoot).cli.binaryPath.value
        .trim()
        .takeIf(String::isNotEmpty)
        ?.let { configured -> Path.of(configured).toAbsolutePath().normalize() }
        ?.takeIf(Files::isExecutable)
    if (configBinary != null) {
        return configBinary
    }

    val installedBinary = Path.of(System.getProperty("user.home"))
        .resolve(".kast")
        .resolve("bin")
        .resolve("kast")
        .toAbsolutePath()
        .normalize()
        .takeIf(Files::isExecutable)
    if (installedBinary != null) {
        return installedBinary
    }

    findExecutableOnPath("kast")
        ?.let { return it }
    findExecutableOnPath("kast-cli")
        ?.let { return it }

    val cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
    return listOf(
        workspaceRoot.resolve("kast-cli/build/scripts/kast-cli"),
        workspaceRoot.resolve("dist/cli/kast-cli"),
        cwd.resolve("kast-cli/build/scripts/kast-cli"),
        cwd.resolve("dist/cli/kast-cli"),
    ).firstOrNull(Files::isExecutable)
}

private fun findExecutableOnPath(commandName: String): Path? {
    val pathEntries = System.getenv("PATH")
                          ?.split(System.getProperty("path.separator", ":"))
                          ?.map(String::trim)
                          ?.filter(String::isNotEmpty)
                      ?: return null
    return pathEntries.asSequence()
        .map { entry -> Path.of(entry).resolve(commandName) }
        .map { candidate -> candidate.toAbsolutePath().normalize() }
        .firstOrNull(Files::isExecutable)
}

private fun daemonLogFile(
    config: KastConfig,
    workspaceRoot: Path,
): Path {
    val workspaceName = workspaceRoot.fileName?.toString()?.ifBlank { "workspace" } ?: "workspace"
    return Path.of(config.paths.logsDir.value).toAbsolutePath().normalize()
        .resolve("$workspaceName-${Instant.now().epochSecond}-standalone-daemon.log")
}
