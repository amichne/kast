package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Detached boundary proof: truncation asserts a discarded located sample, never its population. */
enum class RelationOmissionSampleRetentionDocument {
    COMPLETE,
    TRUNCATED,
}

class RelationOmissionSamplesDocument
private constructor(
    val locations: BoundedProtocolList<RelationOmissionLocationDocument>,
    val retention: RelationOmissionSampleRetentionDocument,
) {
    override fun equals(other: Any?): Boolean =
        other is RelationOmissionSamplesDocument && locations == other.locations && retention == other.retention

    override fun hashCode(): Int = 31 * locations.hashCode() + retention.hashCode()

    override fun toString(): String = "RelationOmissionSamples(retention=$retention, locations=$locations)"

    companion object {
        const val MAXIMUM_SAMPLES = 3

        fun complete(
            locations: BoundedProtocolList<RelationOmissionLocationDocument>
        ): Refinement<RelationOmissionSamplesDocument, RelationOmissionDocumentFailure> =
            admit(locations, RelationOmissionSampleRetentionDocument.COMPLETE)

        fun truncated(
            locations: BoundedProtocolList<RelationOmissionLocationDocument>
        ): Refinement<RelationOmissionSamplesDocument, RelationOmissionDocumentFailure> =
            admit(locations, RelationOmissionSampleRetentionDocument.TRUNCATED)

        private fun admit(
            locations: BoundedProtocolList<RelationOmissionLocationDocument>,
            retention: RelationOmissionSampleRetentionDocument,
        ): Refinement<RelationOmissionSamplesDocument, RelationOmissionDocumentFailure> =
            when {
                locations.values.size > MAXIMUM_SAMPLES ->
                    Refinement.Rejected(RelationOmissionDocumentFailure.TOO_MANY_SAMPLES)
                locations.values.distinct().size != locations.values.size ->
                    Refinement.Rejected(RelationOmissionDocumentFailure.DUPLICATE_SAMPLES)
                retention == RelationOmissionSampleRetentionDocument.TRUNCATED &&
                    locations.values.size != MAXIMUM_SAMPLES ->
                    Refinement.Rejected(RelationOmissionDocumentFailure.INVALID_TRUNCATION)
                else -> Refinement.Refined(RelationOmissionSamplesDocument(locations, retention))
            }
    }
}
