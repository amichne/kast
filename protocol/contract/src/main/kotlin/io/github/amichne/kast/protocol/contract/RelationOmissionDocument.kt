package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

/** Returned facts remain exact; omission evidence describes enumeration completeness separately. */
@Serializable
enum class RelationProviderDocument {
    INTELLIJ_REFERENCES_V1,
    INTELLIJ_DEFINITIONS_V1,
    INTELLIJ_CALLEES_V1,
    INTELLIJ_REFERENCES_V2,
    INTELLIJ_DEFINITIONS_V2,
    INTELLIJ_CALLEES_V2,
}

@Serializable
enum class RelationRemediationDocument {
    INCREASE_READ_LIMIT,
    WAIT_FOR_INDEXING,
    RESOLVE_SOURCE_ERRORS,
    USE_SUPPORTED_DECLARATIONS,
    RETRY_PROVIDER,
    REPAIR_PROVIDER,
}

sealed interface RelationOmissionMeasurementDocument {
    data class ObservedOnPage(val items: RelationObservedItemsDocument) : RelationOmissionMeasurementDocument

    data object UnmeasuredOnPage : RelationOmissionMeasurementDocument
}

@JvmInline
value class RelationObservedItemsDocument private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<RelationObservedItemsDocument, RelationOmissionDocumentFailure> =
            if (raw >= 0) Refinement.Refined(RelationObservedItemsDocument(raw))
            else Refinement.Rejected(RelationOmissionDocumentFailure.NEGATIVE_COUNT)
    }
}

data class RelationOmissionLocationDocument(val file: ProtocolText, val range: SourceRangeDocument)

enum class RelationOmissionDocumentFailure {
    NEGATIVE_COUNT,
    TOO_MANY_SAMPLES,
}

data class RelationOmissionDocument
private constructor(
    val provider: RelationProviderDocument,
    val reason: RelationLimitationDocument,
    val measurement: RelationOmissionMeasurementDocument,
    val samples: BoundedProtocolList<RelationOmissionLocationDocument>,
) {
    val remediation: RelationRemediationDocument
        get() =
            when (reason) {
                RelationLimitationDocument.RESULT_LIMIT_REACHED,
                RelationLimitationDocument.BYTE_LIMIT_REACHED,
                RelationLimitationDocument.WORK_LIMIT_REACHED,
                RelationLimitationDocument.TIME_LIMIT_REACHED -> RelationRemediationDocument.INCREASE_READ_LIMIT
                RelationLimitationDocument.DUMB_MODE_TRANSITION -> RelationRemediationDocument.WAIT_FOR_INDEXING
                RelationLimitationDocument.UNRESOLVED_TARGET -> RelationRemediationDocument.RESOLVE_SOURCE_ERRORS
                RelationLimitationDocument.UNSUPPORTED_ITEM -> RelationRemediationDocument.USE_SUPPORTED_DECLARATIONS
                RelationLimitationDocument.PROVIDER_FAILURE -> RelationRemediationDocument.RETRY_PROVIDER
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
                RelationLimitationDocument.PROVIDER_STALLED -> RelationRemediationDocument.REPAIR_PROVIDER
            }

    companion object {
        private const val MAXIMUM_SAMPLES = 3

        val Empty: BoundedProtocolList<RelationOmissionDocument> =
            (BoundedProtocolList.create(emptyList<RelationOmissionDocument>()) as Refinement.Refined).value

        fun create(
            provider: RelationProviderDocument,
            reason: RelationLimitationDocument,
            measurement: RelationOmissionMeasurementDocument,
            samples: BoundedProtocolList<RelationOmissionLocationDocument>,
        ): Refinement<RelationOmissionDocument, RelationOmissionDocumentFailure> =
            if (samples.values.size <= MAXIMUM_SAMPLES)
                Refinement.Refined(RelationOmissionDocument(provider, reason, measurement, samples))
            else Refinement.Rejected(RelationOmissionDocumentFailure.TOO_MANY_SAMPLES)
    }
}

@Serializable
enum class RelationSoundnessDocument {
    EXACT_RETURNED_FACTS
}
