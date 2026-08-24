package io.github.amichne.kast.cli

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Accepted exact-session capability retained until runtime readiness or terminal startup. */
internal fun interface AcceptedRuntimeStartupSession {
    /** Observes whether the operating-system session authority still owns the identity. */
    fun observe(): LaunchdServiceObservation
}

/** One already-derived process-session identity and its closed lifecycle effects. */
internal interface RuntimeProcessSession : AcceptedRuntimeStartupSession {

    /** Retires a session whose presence was already proven. */
    fun retire(present: LaunchdServiceObservation.Present): LaunchdServiceRetirement
}

internal fun interface RuntimeProcessSessionResolver {
    /**
     * Proof transition: `RuntimeEndpoint -> RuntimeProcessSession`.
     *
     * Establishes the deterministic operating-system session identity for the exact endpoint.
     * Raw session labels remain inside the platform adapter.
     */
    fun resolve(endpoint: RuntimeEndpoint): RuntimeProcessSession
}

internal sealed interface RuntimeProcessSearchResult {
    data object None : RuntimeProcessSearchResult

    data class Exact(
        val process: ProcessHandle,
    ) : RuntimeProcessSearchResult

    data object Ambiguous : RuntimeProcessSearchResult
}

internal fun interface RuntimeProcessSearch {
    /**
     * Proof transition: `RuntimeEndpoint -> RuntimeProcessSearchResult`.
     *
     * [RuntimeProcessSearchResult.Exact] establishes one same-user process carrying every exact
     * endpoint argument. [RuntimeProcessSearchResult] closes absence, inaccessible state, and
     * multiple matches. Raw process metadata remains inside the platform search adapter.
     */
    fun find(endpoint: RuntimeEndpoint): RuntimeProcessSearchResult
}

internal object JdkRuntimeProcessAuthority : RuntimeProcessAuthority by ExactRuntimeProcessAuthority(
    processSearch = JdkRuntimeProcessSearch,
    processSessions = RuntimeProcessSessionResolver(MacOsRuntimeProcessSession::from),
)

/** Resolves process ownership from both live process evidence and OS-session authority. */
internal class ExactRuntimeProcessAuthority(
    private val processSearch: RuntimeProcessSearch,
    private val processSessions: RuntimeProcessSessionResolver,
) : RuntimeProcessAuthority {
    override fun observe(endpoint: RuntimeEndpoint): RuntimeProcessObservation {
        val processSession = processSessions.resolve(endpoint)
        return when (val search = processSearch.find(endpoint)) {
            RuntimeProcessSearchResult.None -> when (processSession.observe()) {
                LaunchdServiceObservation.Present -> RuntimeProcessObservation.Owned(
                    SessionRuntimeOwnedProcess(
                        RuntimeProcessPresence.BetweenSessionAttempts,
                        processSession,
                    ),
                )
                LaunchdServiceObservation.Absent -> RuntimeProcessObservation.Absent
                LaunchdServiceObservation.Interrupted,
                LaunchdServiceObservation.Rejected,
                    -> RuntimeProcessObservation.Ambiguous
            }
            is RuntimeProcessSearchResult.Exact -> RuntimeProcessObservation.Owned(
                SessionRuntimeOwnedProcess(
                    RuntimeProcessPresence.Live(search.process),
                    processSession,
                ),
            )
            RuntimeProcessSearchResult.Ambiguous -> RuntimeProcessObservation.Ambiguous
        }
    }
}

private object JdkRuntimeProcessSearch : RuntimeProcessSearch {
    override fun find(endpoint: RuntimeEndpoint): RuntimeProcessSearchResult {
        val exactArguments = setOf(
            "--workspace-root=${endpoint.root.path}",
            "--socket-path=${endpoint.socketPath}",
            "--runtime-id=${endpoint.runtimeId.value}",
        )
        val current = ProcessHandle.current()
        val currentUser = current.info().user().orElse(null)
            ?: return RuntimeProcessSearchResult.Ambiguous
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
            return RuntimeProcessSearchResult.Ambiguous
        }
        if (inaccessibleIndexer) return RuntimeProcessSearchResult.Ambiguous
        return when (matches.size) {
            0 -> RuntimeProcessSearchResult.None
            1 -> RuntimeProcessSearchResult.Exact(matches.single())
            else -> RuntimeProcessSearchResult.Ambiguous
        }
    }
}

private sealed interface RuntimeProcessPresence {
    data class Live(
        val process: ProcessHandle,
    ) : RuntimeProcessPresence

    /** launchd owns the exact label while no child is visible between restart attempts. */
    data object BetweenSessionAttempts : RuntimeProcessPresence
}

private class SessionRuntimeOwnedProcess(
    private val presence: RuntimeProcessPresence,
    private val processSession: RuntimeProcessSession,
) : RuntimeOwnedProcess {
    override fun terminate(): RuntimeProcessTermination = when (processSession.observe()) {
        LaunchdServiceObservation.Present -> when (
            processSession.retire(LaunchdServiceObservation.Present)
        ) {
            LaunchdServiceRetirement.Retired -> afterSessionRetirement()
            LaunchdServiceRetirement.Interrupted -> RuntimeProcessTermination.Interrupted
            LaunchdServiceRetirement.Rejected -> RuntimeProcessTermination.Rejected
        }
        LaunchdServiceObservation.Absent -> when (val observed = presence) {
            RuntimeProcessPresence.BetweenSessionAttempts -> RuntimeProcessTermination.Terminated
            is RuntimeProcessPresence.Live -> terminateDirectProcess(observed.process)
        }
        LaunchdServiceObservation.Interrupted -> RuntimeProcessTermination.Interrupted
        LaunchdServiceObservation.Rejected -> RuntimeProcessTermination.Rejected
    }

    private fun afterSessionRetirement(): RuntimeProcessTermination = when (val observed = presence) {
        RuntimeProcessPresence.BetweenSessionAttempts -> RuntimeProcessTermination.Terminated
        is RuntimeProcessPresence.Live -> awaitExitAfterSessionRetirement(observed.process)
    }

    private fun awaitExitAfterSessionRetirement(
        process: ProcessHandle,
    ): RuntimeProcessTermination {
        if (!process.isAlive) return RuntimeProcessTermination.Terminated
        return try {
            process.onExit().get(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    private fun terminateDirectProcess(process: ProcessHandle): RuntimeProcessTermination = try {
        if (!process.destroy() && process.isAlive) return RuntimeProcessTermination.Rejected
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

private const val PROCESS_STOP_TIMEOUT_SECONDS = 10L
private const val INDEXER_MAIN_CLASS = "io.github.amichne.kast.indexer.KastIndexerMainKt"
