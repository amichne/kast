package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
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
        val first = queryOnce(root, operation)
        val failure = (first as? WorkspaceDemandResult.Rejected)?.failure as? WorkspaceDemandFailure.Operation
        // No semantic operation was sent when identity inspection detected a changed host.
        return if (failure?.cause == WorkspaceDemandCause.HostChanged) queryOnce(root, operation) else first
    }

    private suspend fun queryOnce(root: CanonicalRoot, operation: ExistingIdeOperation): WorkspaceDemandResult {
        val entry =
            when (val admitted = preparations.prepare(root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return WorkspaceDemandResult.Rejected(WorkspaceDemandFailure.Admission(root, admitted.failure))
            }
        return when (val outcome = entry.state.filterIsInstance<WorkspacePreparationOutcome.Terminal>().first()) {
            is WorkspacePreparationOutcome.Complete -> queryReady(entry, outcome.workspace, operation)
            is WorkspacePreparationOutcome.Rejected ->
                rejected(entry, WorkspaceDemandCause.Preparation(outcome.failure))
            is WorkspacePreparationOutcome.Blocked -> rejected(entry, WorkspaceDemandCause.Lifecycle(outcome.reason))
        }
    }

    private suspend fun queryReady(
        entry: WorkspacePreparation,
        workspace: PreparedWorkspace,
        operation: ExistingIdeOperation,
    ): WorkspaceDemandResult =
        when (val observed = inspect()) {
            is IdeLifecycleResult.Inspected -> {
                val exact = observed.projects.filter { it.target.root == workspace.root.path.toString() }
                if (observed.host == workspace.host.toString() && exact.singleOrNull()?.target == workspace.target)
                    WorkspaceDemandResult.Native(invoke(workspace, operation))
                else {
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
