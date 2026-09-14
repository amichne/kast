package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationWorkCount

/** Finite per-reason counters and at most three detached samples; no provider payloads are retained. */
internal class IntellijRelationOmissionObservation(private val provider: RelationProviderKind) {
    private val counts = mutableMapOf<RelationLimitation, Long>()
    private val samples = mutableMapOf<RelationLimitation, MutableList<RelationOccurrence>>()

    fun record(reason: RelationLimitation, sample: RelationOmissionSample) {
        counts[reason] = (counts[reason] ?: 0L) + 1L
        if (sample is RelationOmissionSample.Located) {
            val retained = samples.getOrPut(reason) { mutableListOf() }
            if (retained.size < RelationOmissionEvidence.MAXIMUM_SAMPLES && sample.occurrence !in retained)
                retained += sample.occurrence
        }
    }

    fun summarize(limitations: Set<RelationLimitation>): List<RelationOmissionEvidence> =
        limitations
            .sortedBy { it.ordinal }
            .map { reason ->
                val measurement =
                    counts[reason]?.let {
                        RelationOmissionMeasurement.ObservedOnPage(
                            (RelationWorkCount.parse(it) as Refinement.Refined).value
                        )
                    } ?: RelationOmissionMeasurement.UnmeasuredOnPage
                RelationOmissionEvidence.fromObservedPage(
                    provider = provider,
                    reason = reason,
                    measurement = measurement,
                    occurrences = samples[reason].orEmpty(),
                )
            }
}
