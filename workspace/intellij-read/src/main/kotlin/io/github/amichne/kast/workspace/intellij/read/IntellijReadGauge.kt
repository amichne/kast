package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

/** Snapshots and high-water marks never accumulate like work counters. */
enum class IntellijReadGauge {
    QUERY_RETAINED_BYTES,
    QUERY_RETAINED_BYTES_HIGH_WATER,
    QUERY_RETAINED_ENTRIES,
    /** Detached bytes required by the observed native inventory attempt, including a rejected final locator. */
    RELATION_INVENTORY_RETAINED_BYTES,
    /** Synchronous native provider preparation for this invocation, separately from retained confirmation. */
    RELATION_PREPARATION_NANOS,
    RELATION_CONFIRMATION_NANOS,
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
        IntellijReadGauge.QUERY_RETAINED_BYTES,
        IntellijReadGauge.QUERY_RETAINED_ENTRIES,
        IntellijReadGauge.RELATION_INVENTORY_RETAINED_BYTES,
        IntellijReadGauge.RELATION_PREPARATION_NANOS,
        IntellijReadGauge.RELATION_CONFIRMATION_NANOS,
        IntellijReadGauge.SOURCE_RETAINED_BYTES,
        IntellijReadGauge.SOURCE_RETAINED_ENTRIES,
        IntellijReadGauge.DIAGNOSTIC_RETAINED_BYTES,
        IntellijReadGauge.DIAGNOSTIC_RETAINED_ENTRIES -> observed
        IntellijReadGauge.QUERY_RETAINED_BYTES_HIGH_WATER,
        IntellijReadGauge.SOURCE_RETAINED_BYTES_HIGH_WATER,
        IntellijReadGauge.DIAGNOSTIC_RETAINED_BYTES_HIGH_WATER ->
            if (previous != null && previous.value > observed.value) previous else observed
    }
