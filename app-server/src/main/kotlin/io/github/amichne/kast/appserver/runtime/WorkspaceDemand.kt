package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedPresemanticRecovery
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
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
}

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
        // No semantic operation was sent when identity inspection detected a changed host.
        return if (failure?.cause == WorkspaceDemandCause.HostChanged && operation !is ExistingIdeOperation.Mutation)
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
            is Refinement.Refined ->
                Refinement.Refined(
                    WorkspaceDemand { selected, operation ->
                        if (selected != root)
                            WorkspaceDemandResult.Rejected(
                                WorkspaceDemandFailure.Admission(selected, WorkspacePreparationFailure.ROOT_REJECTED)
                            )
                        else queryPrepared(ready.value, operation)
                    }
                )
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

    private suspend fun queryPrepared(ready: Ready, operation: ExistingIdeOperation): WorkspaceDemandResult =
        retryPrepared(ready, operation, queryReady(ready.entry, ready.workspace, operation))

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
        when (val observed = inspect()) {
            is IdeLifecycleResult.Inspected -> {
                val exact = observed.projects.filter { it.target.root == workspace.root.path.toString() }
                if (observed.host == workspace.host.toString() && exact.singleOrNull()?.target == workspace.target) {
                    val exchange = invoke(workspace, operation)
                    if (
                        exchange is ExistingIdeExchange.HostRejected &&
                            exchange.recovery == HostedPresemanticRecovery.ModelReload
                    )
                        preparations.invalidate(entry.id, workspace)
                    WorkspaceDemandResult.Native(exchange)
                } else {
                    preparations.invalidate(entry.id, workspace)
                    rejected(entry, WorkspaceDemandCause.HostChanged)
                }
            }
            is IdeLifecycleResult.Blocked -> rejected(entry, WorkspaceDemandCause.Lifecycle(observed.reason))
            else -> rejected(entry, WorkspaceDemandCause.Preparation(WorkspacePreparationFailure.RESPONSE_REJECTED))
        }

    private fun rejected(entry: WorkspacePreparation, cause: WorkspaceDemandCause) =
        WorkspaceDemandResult.Rejected(WorkspaceDemandFailure.Operation(entry.id, entry.root, cause))
}

private fun ExistingIdeExchange.HostRejected.canRetryReadiness(operation: ExistingIdeOperation): Boolean =
    when (operation) {
        is ExistingIdeOperation.Read,
        is ExistingIdeOperation.Classes,
        is ExistingIdeOperation.Supertype -> recovery == HostedPresemanticRecovery.ModelReload || isMovedRead()
        is ExistingIdeOperation.Plan -> recovery == HostedPresemanticRecovery.ModelReload
        is ExistingIdeOperation.Mutation,
        ExistingIdeOperation.Status -> false
    }

/** Refine only the exact schema-admitted read rejection, not text or generic semantic failures. */
private fun ExistingIdeExchange.HostRejected.isMovedRead(): Boolean =
    try {
        readinessJson.decodeFromString<MovedRead>(document.value)
        true
    } catch (_: SerializationException) {
        false
    }

@Serializable
private data class MovedRead(val failure: MovedReadFailure, val detail: MovedReadDetail, val stage: MovedReadStage)

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
