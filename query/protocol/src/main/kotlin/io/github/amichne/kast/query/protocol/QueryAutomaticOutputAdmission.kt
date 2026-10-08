package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnsupportedReason
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest

/** A witness RUN completes one original investigation; retained witness reads remain presentation-only. */
internal fun automaticOutputAdmission(request: QueryRunRequest): Refinement<Unit, QueryRunRejection> {
    if (request !is QueryRunRequest.Run || request.output !is QueryOutputDocument.ImpactWitness)
        return Refinement.Refined(Unit)
    return when (val policy = request.completion) {
        QueryCompletionPolicyDocument.Progressive ->
            Refinement.Rejected(QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED))
        is QueryCompletionPolicyDocument.CompleteOnly ->
            if (request.from is QueryFromDocument.Impact && request.steps.values.isEmpty()) Refinement.Refined(Unit)
            else
                Refinement.Rejected(
                    QueryRunRejection.CompletionUnsupported(
                        policy.model,
                        QueryCompletionUnsupportedReason.UNSUPPORTED_OUTPUT,
                    )
                )
    }
}
