package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolver
import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.protocol.CanonicalDiagnosticCheckProtocol
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.workspace.contract.*

internal class CanonicalDiagnosticCheckHandler(
    private val workspace: WorkspaceInspectionOperations,
    operations: DiagnosticOperations,
    authority: CanonicalProtocolAuthority,
    scopes: DiagnosticScopeResolver,
) :
    OperationHandler<
        DiagnosticCheckRequest,
        DiagnosticCheckResult,
        DiagnosticCheckQualification,
        DiagnosticCheckRejection,
    > {
    private val protocol = CanonicalDiagnosticCheckProtocol(operations, authority, scopes)

    override suspend fun execute(
        request: DiagnosticCheckRequest
    ): OperationOutcome<DiagnosticCheckResult, DiagnosticCheckQualification, DiagnosticCheckRejection> {
        val current =
            (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace?.readLease
                ?: return OperationOutcome.Rejected(DiagnosticCheckRejection.WORKSPACE_NOT_READY)
        val maximum =
            when (val admitted = ResultLimit.parse(request.limit.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(DiagnosticCheckRejection.SCOPE_REJECTED)
            }
        return protocol.execute(request, current, maximum)
    }
}
