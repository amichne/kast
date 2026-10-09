package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCheckpointPartialSymbols
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryRows

/** Strict recovery views pending proven groups without advancing their checkpoint or changing original coverage. */
internal fun completionCheckpointEvidence(
    request: QueryRunRequest.Run,
    accumulated: AccumulatedSymbolQuery,
    policy: QueryInvocationPolicy,
    authority: QueryReferenceAuthority,
): Refinement<AccumulatedSymbolQuery, QueryRunRejection> {
    if (request.completion !is QueryCompletionPolicyDocument.CompleteOnly) return Refinement.Refined(accumulated)
    val output = request.output as? QueryOutputDocument.Symbols ?: return Refinement.Refined(accumulated)
    val original = accumulated.execution as? QueryExecutionResult.Qualified ?: return Refinement.Refined(accumulated)
    val rows = original.result.rows as? QueryRows.Symbols ?: return Refinement.Refined(accumulated)
    val checkpoint =
        (original.continuation as? QueryContinuationState.Resumable)?.checkpoint as? QueryCheckpointPartialSymbols
            ?: return Refinement.Refined(accumulated)
    val capacity = MAX_PROTOCOL_ITEMS - accumulated.items.size
    if (capacity < 1) return Refinement.Refined(accumulated)
    val pending = checkpoint.partialSymbols(ResultLimit.parse(capacity).required(), policy.retainedBytes)
    if (pending.values.isEmpty()) return Refinement.Refined(accumulated)
    if (pending.values.size > capacity || pending.values.any { it.selector.lease != checkpoint.lease })
        return Refinement.Rejected(checkpointEvidenceFailure())
    val projected =
        when (val selected = QueryItemProjector(authority).projectItems(output, pending)) {
            is QueryProjection.Projected -> selected.values
            is QueryProjection.ImpactRejected -> return Refinement.Rejected(selected.cause.presentationRejection())
            QueryProjection.Rejected -> return Refinement.Rejected(checkpointEvidenceFailure())
        }
    return Refinement.Refined(
        accumulated.copy(
            execution =
                original.copy(result = original.result.copy(rows = QueryRows.Symbols.of(rows.values + pending.values))),
            items = accumulated.items + projected,
        )
    )
}

private fun checkpointEvidenceFailure() =
    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
