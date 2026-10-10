package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointStorageAdmission
import io.github.amichne.kast.query.contract.QueryCheckpointStorageObservation
import io.github.amichne.kast.query.contract.QueryCheckpointStorageOutcome
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryTerminalReason

/** The existing capacity decision and its observation share one exact estimate and allowance. */
internal fun PipelineCheckpoint.admitContinuation(
    allowance: QueryByteLimit,
    observation: QueryCheckpointStorageObservation,
): QueryContinuationState {
    val admission = QueryCheckpointStorageAdmission.evaluate(storageEstimate(), allowance)
    observation.observe(admission)
    return when (admission.outcome) {
        QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED ->
            QueryContinuationState.Terminal(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED)
        QueryCheckpointStorageOutcome.WITHIN_LIMIT -> QueryContinuationState.Resumable(this)
    }
}
