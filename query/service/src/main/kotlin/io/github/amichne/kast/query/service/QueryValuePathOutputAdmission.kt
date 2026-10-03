package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryPresentationExecution

/** The evaluator guards standalone bytes; an active paired publisher owns the complete encoded envelope. */
internal fun admitValuePathOutput(
    presentation: QueryPresentationExecution?,
    evaluator: () -> Boolean,
    prepared: () -> Unit,
): Refinement<Boolean, QueryExecutionRejection> =
    when (presentation?.admitValuePath()) {
        null -> Refinement.Refined(evaluator())
        is Refinement.Refined -> {
            prepared()
            Refinement.Refined(true)
        }
        is Refinement.Rejected -> Refinement.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
    }
