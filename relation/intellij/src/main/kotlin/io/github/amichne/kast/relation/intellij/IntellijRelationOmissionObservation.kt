package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationOmissionSamples
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationWorkCount

/** Finite per-reason counters and at most three detached samples; no provider payloads are retained. */
internal class IntellijRelationOmissionObservation(private val provider: RelationProviderKind) {
    private val counts = mutableMapOf<RelationLimitation, Long>()
    private val samples = mutableMapOf<RelationLimitation, RelationOmissionSamples>()

    fun record(reason: RelationLimitation, sample: RelationOmissionSample) {
        counts[reason] = (counts[reason] ?: 0L) + 1L
        if (sample is RelationOmissionSample.Located) {
            val retained = samples[reason] ?: RelationOmissionSamples.Empty
            samples[reason] = retained.observe(sample.occurrence)
        }
    }

    fun observeReferenceOwnership(
        value: io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
    ): Set<RelationLimitation> =
        when (val ownership = value.ownership) {
            is io.github.amichne.kast.relation.contract.RelationReferenceOwnership.Unavailable -> {
                val reason =
                    when (ownership.cause) {
                        io.github.amichne.kast.relation.contract.RelationOwnershipUnavailableCause
                            .UNSUPPORTED_DECLARATION,
                        io.github.amichne.kast.relation.contract.RelationOwnershipUnavailableCause
                            .UNRESOLVED_DECLARATION -> RelationLimitation.UNSUPPORTED_ITEM
                    }
                record(reason, RelationOmissionSample.Located(value.occurrence))
                setOf(reason)
            }
            is io.github.amichne.kast.relation.contract.RelationReferenceOwnership.DeclarationOwned,
            is io.github.amichne.kast.relation.contract.RelationReferenceOwnership.FileScoped -> emptySet()
        }

    fun summarize(
        limitations: Set<RelationLimitation>
    ): Refinement<List<RelationOmissionEvidence>, RelationCompilerRejection> {
        val evidence = mutableListOf<RelationOmissionEvidence>()
        for (reason in limitations.sortedBy { it.ordinal }) {
            val measurement =
                when (val count = counts[reason]) {
                    null -> RelationOmissionMeasurement.UnmeasuredOnPage
                    else ->
                        when (val parsed = RelationWorkCount.parse(count)) {
                            is Refinement.Refined -> RelationOmissionMeasurement.ObservedOnPage(parsed.value)
                            is Refinement.Rejected -> return contractRejected()
                        }
                }
            val observed =
                RelationOmissionEvidence.fromObservedPage(
                    provider = provider,
                    reason = reason,
                    measurement = measurement,
                    samples = samples[reason] ?: RelationOmissionSamples.Empty,
                )
            when (observed) {
                is Refinement.Refined -> evidence += observed.value
                is Refinement.Rejected -> return contractRejected()
            }
        }
        return Refinement.Refined(java.util.List.copyOf(evidence))
    }

    private fun contractRejected() = Refinement.Rejected(RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION)
}
