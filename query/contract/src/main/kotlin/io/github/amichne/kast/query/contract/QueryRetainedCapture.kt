package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/**
 * One invocation keeps successful detached-basis proof, not current host authority. An investigated capture accepts
 * only the same physical ledger and full authority on subsequent pages.
 */
sealed interface QueryRetainedCapture {
    fun capture(
        lease: SemanticReadAuthority,
        execution: QueryExecutionResult,
        observation: QueryRetainedBasisObservation = QueryRetainedBasisObservation.None,
    ): Refinement<Captured, QueryRetainedResultFailure> = Captured.afterValidation(this, lease, execution, observation)

    data object Initial : QueryRetainedCapture

    class Captured private constructor(val result: QueryRetainedResult) : QueryRetainedCapture {
        companion object {
            internal fun afterValidation(
                previous: QueryRetainedCapture,
                lease: SemanticReadAuthority,
                execution: QueryExecutionResult,
                observation: QueryRetainedBasisObservation,
            ): Refinement<Captured, QueryRetainedResultFailure> =
                when (
                    val captured =
                        captureRetainedQueryResult(lease, execution, basis = previous, observation = observation)
                ) {
                    is Refinement.Refined -> Refinement.Refined(Captured(captured.value))
                    is Refinement.Rejected -> captured
                }
        }
    }
}

enum class QueryRetainedBasisEvent {
    LEDGER_CHECK_STARTED,
    LEDGER_PROOF_REUSED,
    LEDGER_PROOF_REJECTED,
}

fun interface QueryRetainedBasisObservation {
    fun observe(event: QueryRetainedBasisEvent)

    data object None : QueryRetainedBasisObservation {
        override fun observe(event: QueryRetainedBasisEvent) = Unit
    }
}

internal fun QueryRetainedCapture.hasForeignLedgerBasis(
    ledger: QueryImpactLedger,
    lease: SemanticReadAuthority,
    observation: QueryRetainedBasisObservation,
): Boolean {
    val captured = (this as? QueryRetainedCapture.Captured)?.result
    val established = (captured as? QueryRetainedResult.ValuePaths)?.rows?.accounting
    if (established is QueryValuePathAccounting.Investigated) {
        val foreign = established.ledger !== ledger || captured.lease != lease
        observation.observe(
            if (foreign) QueryRetainedBasisEvent.LEDGER_PROOF_REJECTED else QueryRetainedBasisEvent.LEDGER_PROOF_REUSED
        )
        return foreign
    }
    observation.observe(QueryRetainedBasisEvent.LEDGER_CHECK_STARTED)
    return ledger.hasForeignBasis(lease)
}
