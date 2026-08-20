package io.github.amichne.kast.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class RuntimeLifecycleState { RUNNING, STOPPED, STALE }
enum class RuntimeEndpointArtifact { SOCKET, DESCRIPTOR, STATE }

sealed interface RuntimeEndpointArtifactObservation {
    data class Observed(
        val present: Set<RuntimeEndpointArtifact>,
    ) : RuntimeEndpointArtifactObservation

    data object Rejected : RuntimeEndpointArtifactObservation
}

sealed interface RuntimeEndpointArtifactCleaning {
    data class Cleaned(
        val removed: Set<RuntimeEndpointArtifact>,
    ) : RuntimeEndpointArtifactCleaning

    data object Rejected : RuntimeEndpointArtifactCleaning
    data object Interrupted : RuntimeEndpointArtifactCleaning
}

interface RuntimeEndpointArtifacts {
    /** Observes only filesystem entries derived from the admitted exact endpoint. */
    fun observe(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactObservation

    /** Deletes only unreachable filesystem entries derived from the admitted exact endpoint. */
    fun clean(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactCleaning
}

sealed interface RuntimeProcessTermination {
    data object Terminated : RuntimeProcessTermination
    data object Rejected : RuntimeProcessTermination
    data object Interrupted : RuntimeProcessTermination
}

fun interface RuntimeOwnedProcess {
    /** Requests termination of one process already proven to own the exact endpoint. */
    fun terminate(): RuntimeProcessTermination
}

sealed interface RuntimeProcessObservation {
    data object Absent : RuntimeProcessObservation

    data class Owned(
        val process: RuntimeOwnedProcess,
    ) : RuntimeProcessObservation

    data object Ambiguous : RuntimeProcessObservation
}

fun interface RuntimeProcessAuthority {
    /**
     * Proof transition: `RuntimeEndpoint -> RuntimeProcessObservation`.
     *
     * Establishes zero or one same-user process whose command carries the endpoint's exact root,
     * socket, and runtime identity. Ambiguous or inaccessible process state fails closed. Raw
     * process arguments remain inside the process-observation adapter.
     */
    fun observe(endpoint: RuntimeEndpoint): RuntimeProcessObservation
}

enum class RuntimeLifecycleFailure {
    ACTIVE_ENDPOINT,
    PROCESS_AMBIGUOUS,
    PROCESS_TERMINATION_FAILED,
    ARTIFACT_CLEAN_FAILED,
    ARTIFACT_OBSERVATION_FAILED,
    INTERRUPTED,
}

sealed interface RuntimeLifecycleResult {
    data class Completed(
        val state: RuntimeLifecycleState,
        val removed: Set<RuntimeEndpointArtifact> = emptySet(),
    ) : RuntimeLifecycleResult

    data class Rejected(
        val failure: RuntimeLifecycleFailure,
    ) : RuntimeLifecycleResult
}

interface RuntimeLifecycleController {
    /** Observes the exact endpoint without starting or acquiring a runtime. */
    fun status(endpoint: RuntimeEndpoint): RuntimeLifecycleResult

    /** Stops only a process proven to own the exact endpoint. */
    fun stop(endpoint: RuntimeEndpoint): RuntimeLifecycleResult

    /** Removes only inactive artifacts derived from the exact endpoint. */
    fun clean(endpoint: RuntimeEndpoint): RuntimeLifecycleResult
}

/** Minimal exact-root lifecycle coordination over existing process and UDS boundaries. */
class ExactRootRuntimeLifecycle(
    private val endpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
    private val processAuthority: RuntimeProcessAuthority = JdkRuntimeProcessAuthority,
    private val artifacts: RuntimeEndpointArtifacts = PosixRuntimeEndpointArtifacts,
) : RuntimeLifecycleController {
    override fun status(endpoint: RuntimeEndpoint): RuntimeLifecycleResult {
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            return RuntimeLifecycleResult.Completed(RuntimeLifecycleState.RUNNING)
        }
        val state = when (val observation = artifacts.observe(endpoint)) {
            RuntimeEndpointArtifactObservation.Rejected -> return RuntimeLifecycleResult.Rejected(
                RuntimeLifecycleFailure.ARTIFACT_OBSERVATION_FAILED,
            )
            is RuntimeEndpointArtifactObservation.Observed -> if (observation.present.isEmpty()) {
                RuntimeLifecycleState.STOPPED
            } else {
                RuntimeLifecycleState.STALE
            }
        }
        return RuntimeLifecycleResult.Completed(state)
    }

    override fun stop(endpoint: RuntimeEndpoint): RuntimeLifecycleResult =
        when (val observation = processAuthority.observe(endpoint)) {
            RuntimeProcessObservation.Absent -> {
                if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
                    RuntimeLifecycleResult.Rejected(RuntimeLifecycleFailure.ACTIVE_ENDPOINT)
                } else {
                    RuntimeLifecycleResult.Completed(RuntimeLifecycleState.STOPPED)
                }
            }
            RuntimeProcessObservation.Ambiguous -> RuntimeLifecycleResult.Rejected(
                RuntimeLifecycleFailure.PROCESS_AMBIGUOUS,
            )
            is RuntimeProcessObservation.Owned -> when (observation.process.terminate()) {
                RuntimeProcessTermination.Terminated -> stoppedIfUnreachable(endpoint)
                RuntimeProcessTermination.Interrupted -> RuntimeLifecycleResult.Rejected(
                    RuntimeLifecycleFailure.INTERRUPTED,
                )
                RuntimeProcessTermination.Rejected -> RuntimeLifecycleResult.Rejected(
                    RuntimeLifecycleFailure.PROCESS_TERMINATION_FAILED,
                )
            }
        }

    override fun clean(endpoint: RuntimeEndpoint): RuntimeLifecycleResult {
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            return RuntimeLifecycleResult.Rejected(RuntimeLifecycleFailure.ACTIVE_ENDPOINT)
        }
        when (processAuthority.observe(endpoint)) {
            RuntimeProcessObservation.Absent -> Unit
            RuntimeProcessObservation.Ambiguous -> return RuntimeLifecycleResult.Rejected(
                RuntimeLifecycleFailure.PROCESS_AMBIGUOUS,
            )
            is RuntimeProcessObservation.Owned -> return RuntimeLifecycleResult.Rejected(
                RuntimeLifecycleFailure.ACTIVE_ENDPOINT,
            )
        }
        return when (val cleaning = artifacts.clean(endpoint)) {
            is RuntimeEndpointArtifactCleaning.Cleaned -> RuntimeLifecycleResult.Completed(
                RuntimeLifecycleState.STOPPED,
                cleaning.removed,
            )
            RuntimeEndpointArtifactCleaning.Rejected -> RuntimeLifecycleResult.Rejected(
                RuntimeLifecycleFailure.ARTIFACT_CLEAN_FAILED,
            )
            RuntimeEndpointArtifactCleaning.Interrupted -> RuntimeLifecycleResult.Rejected(
                RuntimeLifecycleFailure.INTERRUPTED,
            )
        }
    }

    private fun stoppedIfUnreachable(endpoint: RuntimeEndpoint): RuntimeLifecycleResult =
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            RuntimeLifecycleResult.Rejected(RuntimeLifecycleFailure.ACTIVE_ENDPOINT)
        } else {
            RuntimeLifecycleResult.Completed(RuntimeLifecycleState.STOPPED)
        }
}

private object JdkRuntimeProcessAuthority : RuntimeProcessAuthority {
    override fun observe(endpoint: RuntimeEndpoint): RuntimeProcessObservation {
        val exactArguments = setOf(
            "--workspace-root=${endpoint.root.path}",
            "--socket-path=${endpoint.socketPath}",
            "--runtime-id=${endpoint.runtimeId.value}",
        )
        val current = ProcessHandle.current()
        val currentUser = current.info().user().orElse(null)
            ?: return RuntimeProcessObservation.Ambiguous
        val matches = mutableListOf<ProcessHandle>()
        var inaccessibleIndexer = false
        try {
            ProcessHandle.allProcesses().use { processes ->
                processes.forEach { process ->
                    if (process.pid() == current.pid()) return@forEach
                    val info = process.info()
                    val arguments = info.arguments().orElse(null)
                    val commandLine = info.commandLine().orElse("")
                    val isIndexer = arguments?.contains(INDEXER_MAIN_CLASS) == true ||
                        commandLine.contains(INDEXER_MAIN_CLASS)
                    if (!isIndexer) return@forEach
                    val user = info.user().orElse(null)
                    if (user == null || arguments == null) {
                        inaccessibleIndexer = true
                    } else if (user == currentUser && exactArguments.all(arguments::contains)) {
                        matches += process
                    }
                }
            }
        } catch (_: SecurityException) {
            return RuntimeProcessObservation.Ambiguous
        }
        if (inaccessibleIndexer) return RuntimeProcessObservation.Ambiguous
        return when (matches.size) {
            0 -> RuntimeProcessObservation.Absent
            1 -> RuntimeProcessObservation.Owned(JdkRuntimeOwnedProcess(matches.single()))
            else -> RuntimeProcessObservation.Ambiguous
        }
    }
}

private class JdkRuntimeOwnedProcess(
    private val process: ProcessHandle,
) : RuntimeOwnedProcess {
    override fun terminate(): RuntimeProcessTermination {
        if (!process.isAlive) return RuntimeProcessTermination.Terminated
        return try {
            if (!process.destroy() && process.isAlive) {
                return RuntimeProcessTermination.Rejected
            }
            try {
                process.onExit().get(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                if (!process.destroyForcibly() && process.isAlive) {
                    return RuntimeProcessTermination.Rejected
                }
                process.onExit().get(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            RuntimeProcessTermination.Terminated
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            RuntimeProcessTermination.Interrupted
        } catch (_: ExecutionException) {
            RuntimeProcessTermination.Rejected
        } catch (_: TimeoutException) {
            RuntimeProcessTermination.Rejected
        } catch (_: SecurityException) {
            RuntimeProcessTermination.Rejected
        }
    }
}

private object PosixRuntimeEndpointArtifacts : RuntimeEndpointArtifacts {
    override fun observe(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactObservation {
        val paths = RuntimeEndpointArtifactPaths.from(endpoint)
        val present = mutableSetOf<RuntimeEndpointArtifact>()
        return when {
            observe(paths.socket, RuntimeEndpointArtifact.SOCKET, present) == PathObservation.Rejected ->
                RuntimeEndpointArtifactObservation.Rejected
            observe(paths.descriptor, RuntimeEndpointArtifact.DESCRIPTOR, present) == PathObservation.Rejected ->
                RuntimeEndpointArtifactObservation.Rejected
            observe(paths.state, RuntimeEndpointArtifact.STATE, present) == PathObservation.Rejected ->
                RuntimeEndpointArtifactObservation.Rejected
            else -> RuntimeEndpointArtifactObservation.Observed(present)
        }
    }

    override fun clean(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactCleaning {
        val paths = RuntimeEndpointArtifactPaths.from(endpoint)
        val observed = when (val observation = observe(endpoint)) {
            RuntimeEndpointArtifactObservation.Rejected ->
                return RuntimeEndpointArtifactCleaning.Rejected
            is RuntimeEndpointArtifactObservation.Observed -> observation.present
        }
        if (Files.isSymbolicLink(paths.state)) return RuntimeEndpointArtifactCleaning.Rejected
        return when (
            remove(
                buildList {
                    if (RuntimeEndpointArtifact.STATE in observed) add(RemovalTarget.Tree(paths.state))
                    if (RuntimeEndpointArtifact.DESCRIPTOR in observed) {
                        add(RemovalTarget.Entry(paths.descriptor))
                    }
                    if (RuntimeEndpointArtifact.SOCKET in observed) {
                        add(RemovalTarget.Entry(paths.socket))
                    }
                },
            )
        ) {
            RuntimeArtifactRemoval.REMOVED -> when (val remaining = observe(endpoint)) {
                RuntimeEndpointArtifactObservation.Rejected ->
                    RuntimeEndpointArtifactCleaning.Rejected
                is RuntimeEndpointArtifactObservation.Observed -> if (remaining.present.isEmpty()) {
                    RuntimeEndpointArtifactCleaning.Cleaned(observed)
                } else {
                    RuntimeEndpointArtifactCleaning.Rejected
                }
            }
            RuntimeArtifactRemoval.REJECTED -> RuntimeEndpointArtifactCleaning.Rejected
            RuntimeArtifactRemoval.INTERRUPTED -> RuntimeEndpointArtifactCleaning.Interrupted
        }
    }

    /**
     * Proof transition: `List<RemovalTarget> -> RuntimeArtifactRemoval`.
     *
     * Establishes that every exact admitted target is absent after one macOS POSIX removal
     * process per target. [RuntimeArtifactRemoval] closes process rejection and interruption. Raw
     * paths leave only as distinct process arguments at the CLI's admitted process-control edge.
     */
    private fun remove(targets: List<RemovalTarget>): RuntimeArtifactRemoval {
        targets.forEach { target ->
            val arguments = when (target) {
                is RemovalTarget.Entry -> listOf(RM_EXECUTABLE, "-f", "--", target.path.toString())
                is RemovalTarget.Tree -> listOf(RM_EXECUTABLE, "-rf", "--", target.path.toString())
            }
            val exitCode = try {
                ProcessBuilder(arguments)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor()
            } catch (_: IOException) {
                return RuntimeArtifactRemoval.REJECTED
            } catch (_: SecurityException) {
                return RuntimeArtifactRemoval.REJECTED
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return RuntimeArtifactRemoval.INTERRUPTED
            }
            if (exitCode != 0) return RuntimeArtifactRemoval.REJECTED
        }
        return RuntimeArtifactRemoval.REMOVED
    }

    private fun observe(
        path: Path,
        artifact: RuntimeEndpointArtifact,
        present: MutableSet<RuntimeEndpointArtifact>,
    ): PathObservation = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        present += artifact
        PathObservation.Observed
    } catch (_: NoSuchFileException) {
        PathObservation.Absent
    } catch (_: IOException) {
        PathObservation.Rejected
    } catch (_: SecurityException) {
        PathObservation.Rejected
    }
}

private enum class PathObservation { Observed, Absent, Rejected }

private sealed interface RemovalTarget {
    val path: Path

    data class Entry(override val path: Path) : RemovalTarget
    data class Tree(override val path: Path) : RemovalTarget
}

private enum class RuntimeArtifactRemoval { REMOVED, REJECTED, INTERRUPTED }

private data class RuntimeEndpointArtifactPaths(
    val socket: Path,
    val descriptor: Path,
    val state: Path,
) {
    companion object {
        /**
         * Proof transition: `RuntimeEndpoint -> RuntimeEndpointArtifactPaths`.
         *
         * Establishes the sole descriptor and canonical-parent state paths derived from the exact
         * socket. Raw paths remain inside the lifecycle filesystem adapter.
         */
        fun from(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactPaths {
            val socket = endpoint.socketPath
            val parent = socket.parent
            val stateParent = try {
                parent.toRealPath()
            } catch (_: IOException) {
                parent.toAbsolutePath().normalize()
            } catch (_: SecurityException) {
                parent.toAbsolutePath().normalize()
            }
            return RuntimeEndpointArtifactPaths(
                socket,
                socket.resolveSibling("${socket.fileName}.endpoint.json"),
                stateParent.resolve("${socket.fileName}.state"),
            )
        }
    }
}

private const val PROCESS_STOP_TIMEOUT_SECONDS = 10L
private const val RM_EXECUTABLE = "/bin/rm"
private const val INDEXER_MAIN_CLASS = "io.github.amichne.kast.indexer.KastIndexerMainKt"
