package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnsupportedReason
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest

/** Independent row accumulation cannot establish an original investigation ledger's completion. */
internal fun automaticOutputAdmission(request: QueryRunRequest): Refinement<Unit, QueryRunRejection> {
    if (request !is QueryRunRequest.Run) return Refinement.Refined(Unit)
    return when (val policy = request.completion) {
        QueryCompletionPolicyDocument.Progressive -> Refinement.Refined(Unit)
        is QueryCompletionPolicyDocument.CompleteOnly ->
            when (request.output) {
                QueryOutputDocument.ValuePaths,
                is QueryOutputDocument.ImpactWitness ->
                    Refinement.Rejected(
                        QueryRunRejection.CompletionUnsupported(
                            policy.model,
                            QueryCompletionUnsupportedReason.UNSUPPORTED_OUTPUT,
                        )
                    )
                is QueryOutputDocument.Symbols,
                QueryOutputDocument.Occurrences,
                QueryOutputDocument.BindingRows,
                QueryOutputDocument.TraversalRecords -> Refinement.Refined(Unit)
            }
    }
}
