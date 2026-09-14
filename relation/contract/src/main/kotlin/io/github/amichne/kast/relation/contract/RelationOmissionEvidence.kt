package io.github.amichne.kast.relation.contract

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

/** Detached diagnostic evidence. Samples are bounded and cannot confer relation authority. */
data class RelationOmissionEvidence
private constructor(
    val provider: RelationProviderKind,
    val reason: RelationLimitation,
    val measurement: RelationOmissionMeasurement,
    val samples: List<RelationOccurrence>,
) {
    val remediation: RelationRemediation
        get() =
            when (reason) {
                RelationLimitation.RESULT_LIMIT_REACHED,
                RelationLimitation.BYTE_LIMIT_REACHED,
                RelationLimitation.WORK_LIMIT_REACHED,
                RelationLimitation.TIME_LIMIT_REACHED -> RelationRemediation.INCREASE_READ_LIMIT
                RelationLimitation.DUMB_MODE_TRANSITION -> RelationRemediation.WAIT_FOR_INDEXING
                RelationLimitation.UNRESOLVED_TARGET -> RelationRemediation.RESOLVE_SOURCE_ERRORS
                RelationLimitation.UNSUPPORTED_ITEM -> RelationRemediation.USE_SUPPORTED_DECLARATIONS
                RelationLimitation.PROVIDER_FAILURE -> RelationRemediation.RETRY_PROVIDER
                RelationLimitation.PROVIDER_INCOMPLETE,
                RelationLimitation.PROVIDER_STALLED -> RelationRemediation.REPAIR_PROVIDER
            }

    companion object {
        const val MAXIMUM_SAMPLES = 3

        fun fromObservedPage(
            provider: RelationProviderKind,
            reason: RelationLimitation,
            measurement: RelationOmissionMeasurement,
            occurrences: List<RelationOccurrence>,
        ): RelationOmissionEvidence =
            RelationOmissionEvidence(
                provider = provider,
                reason = reason,
                measurement = measurement,
                samples = java.util.Collections.unmodifiableList(occurrences.distinct().take(MAXIMUM_SAMPLES).toList()),
            )
    }
}

sealed interface RelationOmissionSample {
    data class Located(val occurrence: RelationOccurrence) : RelationOmissionSample

    data object Unavailable : RelationOmissionSample
}
