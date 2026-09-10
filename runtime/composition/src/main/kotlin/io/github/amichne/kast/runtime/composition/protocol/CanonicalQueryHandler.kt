package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.runtime.composition.installedSemanticBudgets
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Installed admission delegates syntax, references, execution and projection to the read-only owner. */
internal class CanonicalQueryRunHandler(
    private val workspace: WorkspaceInspectionOperations,
    operations: QueryOperations,
    authority: CanonicalProtocolAuthority,
) : OperationHandler<QueryRunRequest, QueryRunResult, QueryRunQualification, QueryRunRejection> {
    private val protocol = CanonicalQueryProtocol(operations, authority)

    override suspend fun execute(request: QueryRunRequest): OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection> {
        val lease = when (val state = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace.readLease
            else -> return OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady)
        }
        val budget = installedSemanticBudgets()?.query ?: return OperationOutcome.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.BUDGET_REJECTED),
        )
        return protocol.execute(request, lease, budget)
    }
}
