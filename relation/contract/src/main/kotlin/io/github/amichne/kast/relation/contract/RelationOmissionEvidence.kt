package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Counts observed rejected provider items on this page; never estimates the number of missing edges. */
sealed interface RelationOmissionMeasurement {
    data class ObservedOnPage(val items: RelationWorkCount) : RelationOmissionMeasurement

    data object UnmeasuredOnPage : RelationOmissionMeasurement
}

enum class RelationRemediation {
    INCREASE_READ_LIMIT,
    WAIT_FOR_INDEXING,
    RESOLVE_SOURCE_ERRORS,
    USE_SUPPORTED_DECLARATIONS,
    RETRY_PROVIDER,
    REPAIR_PROVIDER,
}

enum class RelationOmissionEvidenceFailure {
    INCONSISTENT_SAMPLE_MEASUREMENT
}

/** Detached diagnostic evidence. Samples are bounded and cannot confer relation authority. */
@ConsistentCopyVisibility
data class RelationOmissionEvidence
private constructor(
    val provider: RelationProviderKind,
    val reason: RelationLimitation,
    val measurement: RelationOmissionMeasurement,
    val samples: RelationOmissionSamples,
) {
    val remediation: RelationRemediation
        get() =
            when (reason) {
                RelationLimitation.RESULT_LIMIT_REACHED,
                RelationLimitation.BYTE_LIMIT_REACHED,
                RelationLimitation.WORK_LIMIT_REACHED,
                RelationLimitation.TIME_LIMIT_REACHED,
                RelationLimitation.CANDIDATE_LIMIT_REACHED,
                RelationLimitation.RETENTION_LIMIT_REACHED -> RelationRemediation.INCREASE_READ_LIMIT
                RelationLimitation.DUMB_MODE_TRANSITION -> RelationRemediation.WAIT_FOR_INDEXING
                RelationLimitation.UNRESOLVED_TARGET -> RelationRemediation.RESOLVE_SOURCE_ERRORS
                RelationLimitation.UNSUPPORTED_ITEM -> RelationRemediation.USE_SUPPORTED_DECLARATIONS
                RelationLimitation.PROVIDER_FAILURE -> RelationRemediation.RETRY_PROVIDER
                RelationLimitation.PROVIDER_INCOMPLETE,
                RelationLimitation.PROVIDER_STALLED,
                RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE -> RelationRemediation.REPAIR_PROVIDER
            }

    companion object {
        fun unmeasured(provider: RelationProviderKind, reason: RelationLimitation): RelationOmissionEvidence =
            RelationOmissionEvidence(
                provider,
                reason,
                RelationOmissionMeasurement.UnmeasuredOnPage,
                RelationOmissionSamples.Empty,
            )

        fun fromObservedPage(
            provider: RelationProviderKind,
            reason: RelationLimitation,
            measurement: RelationOmissionMeasurement,
            samples: RelationOmissionSamples,
        ): Refinement<RelationOmissionEvidence, RelationOmissionEvidenceFailure> {
            val minimum =
                samples.locations.size + if (samples.retention == RelationOmissionSampleRetention.TRUNCATED) 1 else 0
            if (measurement is RelationOmissionMeasurement.ObservedOnPage && measurement.items.value < minimum)
                return Refinement.Rejected(RelationOmissionEvidenceFailure.INCONSISTENT_SAMPLE_MEASUREMENT)
            return Refinement.Refined(
                RelationOmissionEvidence(
                    provider = provider,
                    reason = reason,
                    measurement = measurement,
                    samples = samples,
                )
            )
        }
    }
}

sealed interface RelationOmissionSample {
    data class Located(val occurrence: RelationOccurrence) : RelationOmissionSample

    data object Unavailable : RelationOmissionSample
}
