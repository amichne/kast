package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Records the exact operands at the native refusal without changing capacity decisions. */
internal fun recordValueFlowByteRejection(
    observation: IntellijReadObservation,
    allowance: RelationByteLimit,
    candidateBytes: Long,
) {
    observation.recordValueBytes(IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_ALLOWANCE, allowance.value)
    observation.recordValueBytes(IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_CANDIDATE_BYTES, candidateBytes)
    observation.recordValueBytes(
        IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_OBLIGATION_RESERVE_BYTES,
        reservedValueObligationBytes(),
    )
}

private fun IntellijReadObservation.recordValueBytes(gauge: IntellijReadGauge, bytes: Long) {
    when (val value = IntellijReadGaugeValue.parse(bytes)) {
        is Refinement.Refined -> measure(gauge, value.value)
        is Refinement.Rejected -> error("Detached value capacity cannot be negative")
    }
}

private const val VALUE_OBLIGATION_OVERHEAD_BYTES = 2048L

internal fun reservedValueObligationBytes(): Long =
    VALUE_OBLIGATION_OVERHEAD_BYTES * ValueFlowUnsupportedCause.entries.size.toLong()
