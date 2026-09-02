package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Accepted exact process/session capability retained until readiness or terminal startup. */
internal fun interface AcceptedRuntimeStartupSession {
    /** Observes whether the selected process authority still owns the identity. */
    fun observe(): RuntimeSessionObservation
}

/** One already-derived process-session identity and its closed lifecycle effects. */
internal interface RuntimeProcessSession : AcceptedRuntimeStartupSession {

    /** Retires a session whose presence was already proven. */
    fun retire(present: RuntimeSessionObservation.Present): RuntimeSessionRetirement
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

internal sealed interface RuntimeBootstrapProcessSearchResult {
    data object None : RuntimeBootstrapProcessSearchResult

    data class Exact(
        val process: ProcessHandle,
        val attemptId: SemanticRuntimeBootstrapAttemptId,
    ) : RuntimeBootstrapProcessSearchResult

    data object Ambiguous : RuntimeBootstrapProcessSearchResult
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

internal fun interface RuntimeBootstrapProcessSearch {
    fun find(query: RuntimeBootstrapProcessQuery): RuntimeBootstrapProcessSearchResult
}

internal object JdkRuntimeProcessAuthority : RuntimeProcessAuthority by ExactRuntimeProcessAuthority(
    processSearch = JdkRuntimeProcessSearch,
    processSessions = RuntimeProcessSessionResolver { DirectRuntimeProcessSession },
)

internal object LaunchdRuntimeProcessAuthority : RuntimeProcessAuthority by ExactRuntimeProcessAuthority(
    processSearch = JdkRuntimeProcessSearch,
    processSessions = RuntimeProcessSessionResolver(MacOsRuntimeProcessSession::from),
)

internal object JdkRuntimeBootstrapProcessAuthority :
    RuntimeBootstrapProcessAuthority by ExactRuntimeBootstrapProcessAuthority(
        processSearch = JdkRuntimeBootstrapProcessSearch,
        processSessions = RuntimeProcessSessionResolver { DirectRuntimeProcessSession },
    )

internal object LaunchdRuntimeBootstrapProcessAuthority :
    RuntimeBootstrapProcessAuthority by ExactRuntimeBootstrapProcessAuthority(
        processSearch = JdkRuntimeBootstrapProcessSearch,
        processSessions = RuntimeProcessSessionResolver(MacOsRuntimeProcessSession::from),
    )

/** Direct mode has no authority between process attempts; exact process evidence remains primary. */
private data object DirectRuntimeProcessSession : RuntimeProcessSession {
    override fun observe(): RuntimeSessionObservation = RuntimeSessionObservation.Absent

    override fun retire(
        present: RuntimeSessionObservation.Present,
    ): RuntimeSessionRetirement = when (present) {
        RuntimeSessionObservation.Present -> RuntimeSessionRetirement.Rejected
    }
}

/** Resolves process ownership from both live process evidence and OS-session authority. */
internal class ExactRuntimeProcessAuthority(
    private val processSearch: RuntimeProcessSearch,
    private val processSessions: RuntimeProcessSessionResolver,
) : RuntimeProcessAuthority {
    override fun observe(endpoint: RuntimeEndpoint): RuntimeProcessObservation {
        val processSession = processSessions.resolve(endpoint)
        return when (val search = processSearch.find(endpoint)) {
            RuntimeProcessSearchResult.None -> when (processSession.observe()) {
                RuntimeSessionObservation.Present -> RuntimeProcessObservation.Owned(
                    SessionRuntimeOwnedProcess(
                        RuntimeProcessPresence.BetweenSessionAttempts,
                        processSession,
                    ),
                )
                RuntimeSessionObservation.Absent -> RuntimeProcessObservation.Absent
                RuntimeSessionObservation.Interrupted,
                RuntimeSessionObservation.Rejected,
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

internal class ExactRuntimeBootstrapProcessAuthority(
    private val processSearch: RuntimeBootstrapProcessSearch,
    private val processSessions: RuntimeProcessSessionResolver,
) : RuntimeBootstrapProcessAuthority {
    override fun observe(query: RuntimeBootstrapProcessQuery): RuntimeBootstrapProcessObservation {
        val processSession = processSessions.resolve(query.endpoint)
        return when (val search = processSearch.find(query)) {
            RuntimeBootstrapProcessSearchResult.None -> when (processSession.observe()) {
                RuntimeSessionObservation.Absent -> RuntimeBootstrapProcessObservation.Absent
                RuntimeSessionObservation.Present ->
                    RuntimeBootstrapProcessObservation.Uncorrelated
                RuntimeSessionObservation.Interrupted ->
                    RuntimeBootstrapProcessObservation.Interrupted
                RuntimeSessionObservation.Rejected ->
                    RuntimeBootstrapProcessObservation.Ambiguous
            }
            is RuntimeBootstrapProcessSearchResult.Exact -> RuntimeBootstrapProcessObservation.Owned(
                search.attemptId,
                ProcessHandleRuntimeStartupSession(search.process),
            )
            RuntimeBootstrapProcessSearchResult.Ambiguous ->
                RuntimeBootstrapProcessObservation.Ambiguous
        }
    }
}

/** Finds both the admitted pre-exec launcher and its eventual JVM under one exact query. */
internal object JdkRuntimeBootstrapProcessSearch : RuntimeBootstrapProcessSearch {
    override fun find(query: RuntimeBootstrapProcessQuery): RuntimeBootstrapProcessSearchResult {
        val endpoint = query.endpoint
        val exactArguments = setOf(
            "--workspace-root=${endpoint.root.path}",
            "--socket-path=${endpoint.socketPath}",
            "--runtime-id=${endpoint.runtimeId.value}",
            "--bootstrap-state-path=${query.bootstrapState}",
        )
        val launcher = query.executable.path.toString()
        val current = ProcessHandle.current()
        val currentUser = current.info().user().orElse(null)
            ?: return RuntimeBootstrapProcessSearchResult.Ambiguous
        val matches = mutableListOf<RuntimeBootstrapProcessSearchResult.Exact>()
        var inaccessibleCandidate = false
        try {
            ProcessHandle.allProcesses().use { processes ->
                processes.forEach { process ->
                    if (process.pid() == current.pid()) return@forEach
                    val info = process.info()
                    val arguments = info.arguments().orElse(null)
                    val commandLine = info.commandLine().orElse("")
                    val isCandidate = arguments?.let { values ->
                        values.contains(INDEXER_MAIN_CLASS) || values.contains(launcher)
                    } == true ||
                        commandLine.contains(INDEXER_MAIN_CLASS) || commandLine.contains(launcher)
                    if (!isCandidate) return@forEach
                    val user = info.user().orElse(null)
                    if (user == null || arguments == null) {
                        inaccessibleCandidate = true
                    } else if (user == currentUser && exactArguments.all(arguments::contains)) {
                        val rawAttempt = arguments.singleOrNull { argument ->
                            argument.startsWith(BOOTSTRAP_ATTEMPT_ARGUMENT_PREFIX)
                        }?.removePrefix(BOOTSTRAP_ATTEMPT_ARGUMENT_PREFIX)
                        when (val admitted = rawAttempt?.let(
                            SemanticRuntimeBootstrapAttemptId::admit,
                        )) {
                            is Refinement.Refined -> matches += RuntimeBootstrapProcessSearchResult.Exact(
                                process,
                                admitted.value,
                            )
                            is Refinement.Rejected,
                            null,
                                -> inaccessibleCandidate = true
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            return RuntimeBootstrapProcessSearchResult.Ambiguous
        }
        if (inaccessibleCandidate) return RuntimeBootstrapProcessSearchResult.Ambiguous
        return when (matches.size) {
            0 -> RuntimeBootstrapProcessSearchResult.None
            1 -> matches.single()
            else -> RuntimeBootstrapProcessSearchResult.Ambiguous
        }
    }
}

internal object JdkRuntimeProcessSearch : RuntimeProcessSearch {
    override fun find(endpoint: RuntimeEndpoint): RuntimeProcessSearchResult {
        val exactArguments = setOf(
            "--workspace-root=${endpoint.root.path}",
            "--socket-path=${endpoint.socketPath}",
            "--runtime-id=${endpoint.runtimeId.value}",
        )
        val current = ProcessHandle.current()
        val currentUser = current.info().user().orElse(null)
            ?: return RuntimeProcessSearchResult.Ambiguous
        val matches = mutableListOf<RuntimeProcessSearchResult.Exact>()
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
                        matches += RuntimeProcessSearchResult.Exact(process)
                    }
                }
            }
        } catch (_: SecurityException) {
            return RuntimeProcessSearchResult.Ambiguous
        }
        if (inaccessibleIndexer) return RuntimeProcessSearchResult.Ambiguous
        return when (matches.size) {
            0 -> RuntimeProcessSearchResult.None
            1 -> matches.single()
            else -> RuntimeProcessSearchResult.Ambiguous
        }
    }
}

private class ProcessHandleRuntimeStartupSession(
    private val process: ProcessHandle,
) : AcceptedRuntimeStartupSession {
    override fun observe(): RuntimeSessionObservation = try {
        if (process.isAlive) RuntimeSessionObservation.Present else RuntimeSessionObservation.Absent
    } catch (_: SecurityException) {
        RuntimeSessionObservation.Rejected
    }
}

private sealed interface RuntimeProcessPresence {
    data class Live(
        val process: ProcessHandle,
    ) : RuntimeProcessPresence

    /** An external session owns the exact identity while no child is visible between attempts. */
    data object BetweenSessionAttempts : RuntimeProcessPresence
}

private class SessionRuntimeOwnedProcess(
    private val presence: RuntimeProcessPresence,
    private val processSession: RuntimeProcessSession,
) : RuntimeOwnedProcess {
    override fun terminate(): RuntimeProcessTermination = when (processSession.observe()) {
        RuntimeSessionObservation.Present -> when (
            processSession.retire(RuntimeSessionObservation.Present)
        ) {
            RuntimeSessionRetirement.Retired -> afterSessionRetirement()
            RuntimeSessionRetirement.Interrupted -> RuntimeProcessTermination.Interrupted
            RuntimeSessionRetirement.Rejected -> RuntimeProcessTermination.Rejected
        }
        RuntimeSessionObservation.Absent -> when (val observed = presence) {
            RuntimeProcessPresence.BetweenSessionAttempts -> RuntimeProcessTermination.Terminated
            is RuntimeProcessPresence.Live -> terminateDirectProcess(observed.process)
        }
        RuntimeSessionObservation.Interrupted -> RuntimeProcessTermination.Interrupted
        RuntimeSessionObservation.Rejected -> RuntimeProcessTermination.Rejected
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
private const val BOOTSTRAP_ATTEMPT_ARGUMENT_PREFIX = "--bootstrap-attempt-id="
