package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.protocol.QueryRetentionMeasurements
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** The same finite gauge contract measures each existing owner independently of process memory. */
internal fun HostedSemanticReadContext.measureRetainedState(
    owner: HostedRetentionOwner,
    snapshot: QueryRetentionMeasurements,
) {
    val gauges =
        when (owner) {
            HostedRetentionOwner.SOURCE ->
                HostedRetentionGauges(
                    IntellijReadGauge.SOURCE_RETAINED_BYTES,
                    IntellijReadGauge.SOURCE_RETAINED_BYTES_HIGH_WATER,
                    IntellijReadGauge.SOURCE_RETAINED_ENTRIES,
                )
            HostedRetentionOwner.DIAGNOSTIC ->
                HostedRetentionGauges(
                    IntellijReadGauge.DIAGNOSTIC_RETAINED_BYTES,
                    IntellijReadGauge.DIAGNOSTIC_RETAINED_BYTES_HIGH_WATER,
                    IntellijReadGauge.DIAGNOSTIC_RETAINED_ENTRIES,
                )
        }
    fun record(gauge: IntellijReadGauge, value: Long) =
        when (val measured = IntellijReadGaugeValue.parse(value)) {
            is Refinement.Refined -> observation.measure(gauge, measured.value)
            is Refinement.Rejected -> error("Bounded retention accounting cannot produce a negative measurement")
        }
    record(gauges.bytes, snapshot.retainedBytes.value)
    record(gauges.highWaterBytes, snapshot.highWaterBytes.value)
    record(gauges.entries, snapshot.retainedEntries.value.toLong())
}

private data class HostedRetentionGauges(
    val bytes: IntellijReadGauge,
    val highWaterBytes: IntellijReadGauge,
    val entries: IntellijReadGauge,
)
