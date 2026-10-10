package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

/** Snapshots and high-water marks never accumulate like work counters. */
enum class IntellijReadGauge {
    /** Last checkpoint admission's unchanged conservative accounting; never measured heap or wire bytes. */
    QUERY_CHECKPOINT_ALLOWANCE,
    QUERY_CHECKPOINT_REQUIRED_BYTES,
    QUERY_CHECKPOINT_TASK_BYTES,
    QUERY_CHECKPOINT_IDENTITY_ROW_BYTES,
    QUERY_CHECKPOINT_INPUT_BYTES,
    QUERY_CHECKPOINT_IMPACT_BYTES,
    QUERY_CHECKPOINT_JOIN_BYTES,
    /** Latest rejected checkpoint snapshot survives subsequent successful checkpoint admissions. */
    QUERY_CHECKPOINT_REJECTION_ALLOWANCE,
    QUERY_CHECKPOINT_REJECTION_REQUIRED_BYTES,
    QUERY_CHECKPOINT_REJECTION_TASK_BYTES,
    QUERY_CHECKPOINT_REJECTION_IDENTITY_ROW_BYTES,
    QUERY_CHECKPOINT_REJECTION_INPUT_BYTES,
    QUERY_CHECKPOINT_REJECTION_IMPACT_BYTES,
    QUERY_CHECKPOINT_REJECTION_JOIN_BYTES,
    QUERY_RETAINED_BYTES,
    QUERY_RETAINED_BYTES_HIGH_WATER,
    QUERY_RETAINED_ENTRIES,
    /** Detached bytes required by the observed native inventory attempt, including a rejected final locator. */
    RELATION_INVENTORY_RETAINED_BYTES,
    /** The current relation page's grant, which may be smaller than the hosted invocation budget. */
    RELATION_ELAPSED_LIMIT_MILLIS,
    /** Time already charged to that grant before inventory admission, including subject revalidation and retries. */
    RELATION_ELAPSED_BEFORE_PREPARATION_NANOS,
    /** Synchronous native provider preparation for this invocation, separately from retained confirmation. */
    RELATION_PREPARATION_NANOS,
    RELATION_CONFIRMATION_NANOS,
    /** Last callback-proof retention attempt's allowance; conservative detached accounting, not wire bytes. */
    CALLBACK_PROOF_BYTE_ALLOWANCE,
    /** Last attempt ledger's admitted conservative storage; independent ledgers are not summed. */
    CALLBACK_PROOF_RETAINED_BYTES,
    /** Admitted storage plus attempted addition, saturated on overflow; rejection does not charge it. */
    CALLBACK_PROOF_REQUIRED_BYTES,
    /** Latest byte rejection snapshots below belong to one ledger and survive later successful attempts. */
    CALLBACK_PROOF_BYTE_REJECTION_ALLOWANCE,
    CALLBACK_PROOF_BYTE_REJECTION_RETAINED_BYTES,
    CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES,
    SOURCE_RETAINED_BYTES,
    SOURCE_RETAINED_BYTES_HIGH_WATER,
    SOURCE_RETAINED_ENTRIES,
    DIAGNOSTIC_RETAINED_BYTES,
    DIAGNOSTIC_RETAINED_BYTES_HIGH_WATER,
    DIAGNOSTIC_RETAINED_ENTRIES,
}

@Serializable
@JvmInline
value class IntellijReadGaugeValue private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): Refinement<IntellijReadGaugeValue, IntellijReadGaugeFailure> =
            if (value < 0L) Refinement.Rejected(IntellijReadGaugeFailure.NEGATIVE_MEASUREMENT)
            else Refinement.Refined(IntellijReadGaugeValue(value))
    }
}

enum class IntellijReadGaugeFailure {
    NEGATIVE_MEASUREMENT
}

internal fun IntellijReadGauge.merge(
    previous: IntellijReadGaugeValue?,
    observed: IntellijReadGaugeValue,
): IntellijReadGaugeValue =
    when (this) {
        IntellijReadGauge.QUERY_CHECKPOINT_ALLOWANCE,
        IntellijReadGauge.QUERY_CHECKPOINT_REQUIRED_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_TASK_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_IDENTITY_ROW_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_INPUT_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_IMPACT_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_JOIN_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_ALLOWANCE,
        IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_REQUIRED_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_TASK_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_IDENTITY_ROW_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_INPUT_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_IMPACT_BYTES,
        IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_JOIN_BYTES,
        IntellijReadGauge.QUERY_RETAINED_BYTES,
        IntellijReadGauge.QUERY_RETAINED_ENTRIES,
        IntellijReadGauge.RELATION_INVENTORY_RETAINED_BYTES,
        IntellijReadGauge.RELATION_ELAPSED_LIMIT_MILLIS,
        IntellijReadGauge.RELATION_ELAPSED_BEFORE_PREPARATION_NANOS,
        IntellijReadGauge.RELATION_PREPARATION_NANOS,
        IntellijReadGauge.RELATION_CONFIRMATION_NANOS,
        IntellijReadGauge.CALLBACK_PROOF_BYTE_ALLOWANCE,
        IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES,
        IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES,
        IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_ALLOWANCE,
        IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_RETAINED_BYTES,
        IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES,
        IntellijReadGauge.SOURCE_RETAINED_BYTES,
        IntellijReadGauge.SOURCE_RETAINED_ENTRIES,
        IntellijReadGauge.DIAGNOSTIC_RETAINED_BYTES,
        IntellijReadGauge.DIAGNOSTIC_RETAINED_ENTRIES -> observed
        IntellijReadGauge.QUERY_RETAINED_BYTES_HIGH_WATER,
        IntellijReadGauge.SOURCE_RETAINED_BYTES_HIGH_WATER,
        IntellijReadGauge.DIAGNOSTIC_RETAINED_BYTES_HIGH_WATER ->
            if (previous != null && previous.value > observed.value) previous else observed
    }
