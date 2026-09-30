package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

enum class TraversalExpansionRemainderDocument {
    CONTINUATION_RETAINED,
    NOT_EXPLORED,
}

enum class TraversalPartialExpansionDocumentFailure {
    EMPTY_LIMITATIONS,
    INCONSISTENT_OMISSIONS,
}

/** Page-local qualified node reads. Depth belongs to the subject node, not its returned edges. */
@ConsistentCopyVisibility
data class TraversalPartialExpansionDocument
private constructor(
    val subject: ProtocolText,
    val depth: TraversalDepthDocument,
    val limitations: List<RelationLimitationDocument>,
    val remainder: TraversalExpansionRemainderDocument,
    val knownMinimum: QueryKnownMinimum,
    val omissions: BoundedProtocolList<RelationOmissionDocument>,
) {
    companion object {
        val Empty: BoundedProtocolList<TraversalPartialExpansionDocument> =
            (BoundedProtocolList.create(emptyList<TraversalPartialExpansionDocument>()) as Refinement.Refined).value

        fun create(
            subject: ProtocolText,
            depth: TraversalDepthDocument,
            limitations: List<RelationLimitationDocument>,
            remainder: TraversalExpansionRemainderDocument,
            knownMinimum: QueryKnownMinimum,
            omissions: BoundedProtocolList<RelationOmissionDocument>,
        ): Refinement<TraversalPartialExpansionDocument, TraversalPartialExpansionDocumentFailure> =
            if (limitations.isEmpty()) Refinement.Rejected(TraversalPartialExpansionDocumentFailure.EMPTY_LIMITATIONS)
            else if (omissions.values.map { it.reason } != limitations.distinct().sortedBy { it.ordinal })
                Refinement.Rejected(TraversalPartialExpansionDocumentFailure.INCONSISTENT_OMISSIONS)
            else
                Refinement.Refined(
                    TraversalPartialExpansionDocument(
                        subject,
                        depth,
                        limitations.distinct().sortedBy { it.ordinal },
                        remainder,
                        knownMinimum,
                        omissions,
                    )
                )
    }
}
