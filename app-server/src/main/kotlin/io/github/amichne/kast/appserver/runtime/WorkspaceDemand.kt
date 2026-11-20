package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostIncarnationFailure
import io.github.amichne.kast.appserver.ide.HostedPresemanticRecovery
import io.github.amichne.kast.appserver.ide.ObservedHost
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal fun interface WorkspaceDemand {
    suspend fun query(root: CanonicalRoot, operation: ExistingIdeOperation): WorkspaceDemandResult
}

/** Explicit existing-host adapter retained for direct callers and narrow provider fixtures. */
internal class ExistingWorkspaceDemand(
    private val client: ExistingIdeClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WorkspaceDemand {
    override suspend fun query(root: CanonicalRoot, operation: ExistingIdeOperation): WorkspaceDemandResult =
        WorkspaceDemandResult.Native(runInterruptible(dispatcher) { client.query(root, operation) })
}

internal sealed interface WorkspaceDemandResult {
    data class Native(val exchange: ExistingIdeExchange) : WorkspaceDemandResult

    data class Rejected(val failure: WorkspaceDemandFailure) : WorkspaceDemandResult
}

internal sealed interface WorkspaceDemandFailure {
    data class Admission(val root: CanonicalRoot, val failure: WorkspacePreparationFailure) : WorkspaceDemandFailure

    data class Operation(val id: WorkspacePreparationId, val root: CanonicalRoot, val cause: WorkspaceDemandCause) :
        WorkspaceDemandFailure
}

@Serializable
sealed interface WorkspaceDemandCause {
    @Serializable
    @SerialName("preparation")
    data class Preparation(val failure: WorkspacePreparationFailure) : WorkspaceDemandCause

    @Serializable @SerialName("lifecycle") data class Lifecycle(val reason: IdeLifecycleFailure) : WorkspaceDemandCause

    @Serializable @SerialName("host_changed") data object HostChanged : WorkspaceDemandCause

    @Serializable
    @SerialName("host_incarnation")
    data class HostIncarnation(val reason: HostIncarnationFailure) : WorkspaceDemandCause
}

/** [confirmReady] inspects the current Host for this exact retained target. It sends no semantic operation. */
internal data class PreparedWorkspaceAuthority(
    val demand: WorkspaceDemand,
    val host: ObservedHost,
    val preparationId: WorkspacePreparationId,
    val confirmReady: suspend () -> Refinement<Unit, WorkspaceDemandFailure>,
)

internal class PreparedWorkspaceDemand(
    private val preparations: WorkspacePreparations,
    private val inspect: suspend () -> IdeLifecycleResult,
    private val invoke: suspend (PreparedWorkspace, ExistingIdeOperation) -> ExistingIdeExchange,
) : WorkspaceDemand {
    override suspend fun query(root: CanonicalRoot, operation: ExistingIdeOperation): WorkspaceDemandResult {
        val ready =
            when (val prepared = prepareReady(root)) {
                is Refinement.Rejected -> return WorkspaceDemandResult.Rejected(prepared.failure)
                is Refinement.Refined -> prepared.value
            }
        val first = queryReady(ready.entry, ready.workspace, operation)
        val failure = (first as? WorkspaceDemandResult.Rejected)?.failure as? WorkspaceDemandFailure.Operation
        // No semantic operation was sent when inspection found a changed or exited host, so a fresh preparation may
        // launch or select its replacement. A mutation is never replayed against another Host incarnation.
        return if (failure?.cause in REPREPARABLE && operation !is ExistingIdeOperation.Mutation)
            queryOnce(root, operation)
        else retryPrepared(ready, operation, first)
    }

    private suspend fun queryOnce(root: CanonicalRoot, operation: ExistingIdeOperation): WorkspaceDemandResult =
        when (val ready = prepareReady(root)) {
            is Refinement.Rejected -> WorkspaceDemandResult.Rejected(ready.failure)
            is Refinement.Refined -> queryReady(ready.value.entry, ready.value.workspace, operation)
        }

    /** Captures exact authority. Readiness recovery cannot substitute a different Host or project. */
    suspend fun prepare(root: CanonicalRoot): Refinement<WorkspaceDemand, WorkspaceDemandFailure> =
        when (val ready = prepareReady(root)) {
            is Refinement.Rejected -> ready
            is Refinement.Refined -> Refinement.Refined(capture(root, ready.value))
        }

    suspend fun prepareWithHost(
        root: CanonicalRoot,
        observeHost: suspend (IdeProjectTarget) -> Refinement<ObservedHost, HostIncarnationFailure>,
    ): Refinement<PreparedWorkspaceAuthority, WorkspaceDemandFailure> {
        val ready =
            when (val prepared = prepareReady(root)) {
                is Refinement.Rejected -> return prepared
                is Refinement.Refined -> prepared.value
            }
        val host =
            when (val observed = observeHost(ready.workspace.target)) {
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        WorkspaceDemandFailure.Operation(
                            ready.entry.id,
                            root,
                            WorkspaceDemandCause.HostIncarnation(observed.failure),
                        )
                    )
                is Refinement.Refined -> observed.value
            }
        return Refinement.Refined(
            PreparedWorkspaceAuthority(capture(root, ready), host, ready.entry.id) {
                confirmReady(ready.entry, ready.workspace)
            }
        )
    }

    private fun capture(root: CanonicalRoot, ready: Ready) = WorkspaceDemand { selected, operation ->
        if (selected != root)
            WorkspaceDemandResult.Rejected(
                WorkspaceDemandFailure.Admission(selected, WorkspacePreparationFailure.ROOT_REJECTED)
            )
        else queryPrepared(ready, operation)
    }

    private data class Ready(val entry: WorkspacePreparation, val workspace: PreparedWorkspace)

    private suspend fun prepareReady(root: CanonicalRoot): Refinement<Ready, WorkspaceDemandFailure> {
        val entry =
            when (val admitted = preparations.prepareForDemand(root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(WorkspaceDemandFailure.Admission(root, admitted.failure))
            }
        return when (val outcome = entry.state.filterIsInstance<WorkspacePreparationOutcome.Terminal>().first()) {
            is WorkspacePreparationOutcome.Complete -> Refinement.Refined(Ready(entry, outcome.workspace))
            is WorkspacePreparationOutcome.Rejected ->
                Refinement.Rejected(
                    WorkspaceDemandFailure.Operation(entry.id, root, WorkspaceDemandCause.Preparation(outcome.failure))
                )
            is WorkspacePreparationOutcome.Blocked ->
                Refinement.Rejected(
                    WorkspaceDemandFailure.Operation(entry.id, root, WorkspaceDemandCause.Lifecycle(outcome.reason))
                )
        }
    }

    /** Each held operation starts from the current completed record for the same exact target. */
    private suspend fun queryPrepared(held: Ready, operation: ExistingIdeOperation): WorkspaceDemandResult {
        val (entry, workspace) = preparations.current(held.entry, held.workspace)
        val ready = Ready(entry, workspace)
        return retryPrepared(ready, operation, queryReady(entry, workspace, operation))
    }

    private suspend fun retryPrepared(
        ready: Ready,
        operation: ExistingIdeOperation,
        first: WorkspaceDemandResult,
    ): WorkspaceDemandResult {
        val exchange = (first as? WorkspaceDemandResult.Native)?.exchange
        return if (exchange is ExistingIdeExchange.HostRejected && exchange.canRetryReadiness(operation)) {
            preparations.invalidate(ready.entry.id, ready.workspace)
            when (val refreshed = prepareReady(ready.workspace.root)) {
                is Refinement.Rejected -> WorkspaceDemandResult.Rejected(refreshed.failure)
                is Refinement.Refined ->
                    if (refreshed.value.workspace.target == ready.workspace.target)
                        // Deliberately call the non-retrying path: at most two semantic attempts per request.
                        queryReady(refreshed.value.entry, refreshed.value.workspace, operation)
                    else rejected(ready.entry, WorkspaceDemandCause.HostChanged)
            }
        } else first
    }

    private suspend fun queryReady(
        entry: WorkspacePreparation,
        workspace: PreparedWorkspace,
        operation: ExistingIdeOperation,
    ): WorkspaceDemandResult =
        when (val current = confirmReady(entry, workspace)) {
            is Refinement.Rejected -> WorkspaceDemandResult.Rejected(current.failure)
            is Refinement.Refined -> {
                val exchange = invoke(workspace, operation)
                if (
                    exchange is ExistingIdeExchange.HostRejected &&
                        exchange.recovery == HostedPresemanticRecovery.ModelReload
                )
                    preparations.invalidate(entry.id, workspace)
                WorkspaceDemandResult.Native(exchange)
            }
        }

    /** Admits only the same Host with exactly this project target; a stale or exited target invalidates [entry]. */
    private suspend fun confirmReady(
        entry: WorkspacePreparation,
        workspace: PreparedWorkspace,
    ): Refinement<Unit, WorkspaceDemandFailure> =
        when (val observed = inspect()) {
            is IdeLifecycleResult.Inspected -> {
                val exact = observed.projects.filter { it.target.root == workspace.root.path.toString() }
                if (observed.host == workspace.host.toString() && exact.singleOrNull()?.target == workspace.target)
                    Refinement.Refined(Unit)
                else {
                    preparations.invalidate(entry.id, workspace)
                    Refinement.Rejected(failure(entry, WorkspaceDemandCause.HostChanged))
                }
            }
            is IdeLifecycleResult.Blocked -> {
                // An exited Host, such as one stopped by idle shutdown, never answers this preparation again.
                if (observed.reason == IdeLifecycleFailure.HOST_UNAVAILABLE)
                    preparations.invalidate(entry.id, workspace)
                Refinement.Rejected(failure(entry, WorkspaceDemandCause.Lifecycle(observed.reason)))
            }
            else ->
                Refinement.Rejected(
                    failure(entry, WorkspaceDemandCause.Preparation(WorkspacePreparationFailure.RESPONSE_REJECTED))
                )
        }

    private fun rejected(entry: WorkspacePreparation, cause: WorkspaceDemandCause) =
        WorkspaceDemandResult.Rejected(failure(entry, cause))

    private fun failure(entry: WorkspacePreparation, cause: WorkspaceDemandCause) =
        WorkspaceDemandFailure.Operation(entry.id, entry.root, cause)

    private companion object {
        val REPREPARABLE: Set<WorkspaceDemandCause> =
            setOf(
                WorkspaceDemandCause.HostChanged,
                WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE),
            )
    }
}

private fun ExistingIdeExchange.HostRejected.canRetryReadiness(operation: ExistingIdeOperation): Boolean =
    when (operation) {
        is ExistingIdeOperation.Read,
        is ExistingIdeOperation.Classes,
        is ExistingIdeOperation.Supertype ->
            recovery == HostedPresemanticRecovery.ModelReload || isMovedHostedRead(document.value)
        is ExistingIdeOperation.Plan -> recovery == HostedPresemanticRecovery.ModelReload
        is ExistingIdeOperation.Mutation,
        ExistingIdeOperation.Status -> false
    }

/** Refine only the exact schema-admitted read rejection, not text or generic semantic failures. */
fun isMovedHostedRead(document: String): Boolean =
    try {
        readinessJson.decodeFromString<MovedRead>(document).schemaVersion == 1
    } catch (_: SerializationException) {
        false
    }

@Serializable
private data class MovedRead(
    val schemaVersion: Int,
    val outcome: MovedReadOutcome,
    val failure: MovedReadFailure,
    val detail: MovedReadDetail,
    val stage: MovedReadStage,
)

@Serializable
private enum class MovedReadOutcome {
    @SerialName("rejected") REJECTED
}

@Serializable
private enum class MovedReadFailure {
    FRESHNESS_REJECTED
}

@Serializable
private enum class MovedReadDetail {
    MOVED
}

@Serializable
private enum class MovedReadStage {
    MODEL_CAPTURE,
    CONTENT_REVALIDATION,
}

private val readinessJson = Json { ignoreUnknownKeys = true }
