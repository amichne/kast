package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionLimitationsDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.query.contract.QueryExecutionResult

internal fun rejectInvocationCompletion(
    failure: QueryCompletionFailure,
    accumulated: AccumulatedSymbolQuery,
    progress: QueryQualifiedProgressDocument?,
    retain: () -> Refinement<QueryCompletionEvidenceDocument, QueryRunRejection>,
): QueryPublishedPage {
    val originalFailure =
        when (val admitted = optionalOriginalFailure(accumulated.failure)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return OperationOutcome.Rejected(admitted.failure)
        }
    val coverage =
        when (val original = accumulated.execution) {
            is QueryExecutionResult.Complete -> QueryCompletionCoverageDocument.Complete
            is QueryExecutionResult.Qualified ->
                QueryCompletionCoverageDocument.Qualified(
                    ProtocolOffset.parse(original.coverage.knownMinimum.value).required(),
                    QueryCompletionLimitationsDocument.from(
                            original.coverage.limitations.map { QueryLimitationDocument.valueOf(it.name) }
                        )
                        .required(),
                    progress
                        ?: QueryQualifiedProgressDocument.TerminalIncomplete(
                            QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                        ),
                )
            is QueryExecutionResult.Rejection -> return OperationOutcome.Rejected(invocationContractFailure())
        }
    val retained =
        when (val captured = retain()) {
            is Refinement.Rejected -> return OperationOutcome.Rejected(captured.failure)
            is Refinement.Refined -> captured.value
        }
    return OperationOutcome.Rejected(
        QueryRunRejection.CompletionUnproven(
            QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
            failure.protocolCompletionCause(),
            coverage,
            accumulated.stop,
            retained,
            originalFailure = originalFailure,
        )
    )
}

private fun invocationContractFailure() =
    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
