package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryCheckpointStorageObservation
import io.github.amichne.kast.query.contract.QueryCheckpointStorageOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** One finite snapshot per existing checkpoint decision; no retained source, paths or identities. */
internal fun hostedQueryCheckpointStorageObservation(observation: IntellijReadObservation) =
    QueryCheckpointStorageObservation { admission ->
        val estimate = admission.estimate
        observation.measureCheckpoint(IntellijReadGauge.QUERY_CHECKPOINT_ALLOWANCE, admission.allowance.value)
        observation.measureCheckpoint(IntellijReadGauge.QUERY_CHECKPOINT_REQUIRED_BYTES, estimate.required.value)
        observation.measureCheckpoint(IntellijReadGauge.QUERY_CHECKPOINT_TASK_BYTES, estimate.tasks.value)
        observation.measureCheckpoint(
            IntellijReadGauge.QUERY_CHECKPOINT_IDENTITY_ROW_BYTES,
            estimate.identityRows.value,
        )
        observation.measureCheckpoint(IntellijReadGauge.QUERY_CHECKPOINT_INPUT_BYTES, estimate.inputs.value)
        observation.measureCheckpoint(IntellijReadGauge.QUERY_CHECKPOINT_IMPACT_BYTES, estimate.impact.value)
        observation.measureCheckpoint(IntellijReadGauge.QUERY_CHECKPOINT_JOIN_BYTES, estimate.joins.value)
        when (admission.outcome) {
            QueryCheckpointStorageOutcome.WITHIN_LIMIT ->
                observation.count(IntellijReadCounter.QUERY_CHECKPOINT_WITHIN_LIMIT)
            QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED -> {
                observation.count(IntellijReadCounter.QUERY_CHECKPOINT_CAPACITY_EXCEEDED)
                observation.measureCheckpoint(
                    IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_ALLOWANCE,
                    admission.allowance.value,
                )
                observation.measureCheckpoint(
                    IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_REQUIRED_BYTES,
                    estimate.required.value,
                )
                observation.measureCheckpoint(
                    IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_TASK_BYTES,
                    estimate.tasks.value,
                )
                observation.measureCheckpoint(
                    IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_IDENTITY_ROW_BYTES,
                    estimate.identityRows.value,
                )
                observation.measureCheckpoint(
                    IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_INPUT_BYTES,
                    estimate.inputs.value,
                )
                observation.measureCheckpoint(
                    IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_IMPACT_BYTES,
                    estimate.impact.value,
                )
                observation.measureCheckpoint(
                    IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_JOIN_BYTES,
                    estimate.joins.value,
                )
            }
        }
    }

private fun IntellijReadObservation.measureCheckpoint(gauge: IntellijReadGauge, bytes: Long) {
    when (val value = IntellijReadGaugeValue.parse(bytes)) {
        is Refinement.Refined -> measure(gauge, value.value)
        is Refinement.Rejected -> error("Checkpoint observation lost its nonnegative storage proof")
    }
}
