package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedFindingClassificationTest {
    @Test
    fun `a compact content relationship cannot claim exact collection identity`() {
        val f = ImpactReviewedValueFixture()
        val relation =
            ImpactReviewedValueRelationshipDocument.fromBinding(
                f.binding,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
            )
        val finding =
            ImpactFindingFixture()
                .finding()
                .copy(
                    representation = ImpactFindingRepresentationDocument.NotModeled,
                    reviewedRelations = f.bounded(listOf(relation)),
                    classification = ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.EXACT),
                )
        assertTrue(relation.admits(relation.enclosing))
        assertFalse(finding.reviewedEvidenceHasSourceBasis(f.binding.basis))
    }

    @Test
    fun `a compact content relationship keeps derived and weaker model classifications`() {
        val f = ImpactReviewedValueFixture()
        val relation =
            ImpactReviewedValueRelationshipDocument.fromBinding(
                f.binding,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER,
            )
        val finding =
            ImpactFindingFixture()
                .finding()
                .copy(
                    representation = ImpactFindingRepresentationDocument.NotModeled,
                    reviewedRelations = f.bounded(listOf(relation)),
                    classification = ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED),
                )
        assertTrue(finding.reviewedEvidenceHasSourceBasis(f.binding.basis))
        assertTrue(
            finding
                .copy(
                    classification =
                        ImpactPathClassificationDocument.NotEstablished(
                            (ProtocolOffset.parse(0) as Refinement.Refined).value,
                            ImpactCorrespondenceFailureDocument.REPRESENTATION_MODEL_ONLY,
                        )
                )
                .reviewedEvidenceHasSourceBasis(f.binding.basis)
        )
    }
}
