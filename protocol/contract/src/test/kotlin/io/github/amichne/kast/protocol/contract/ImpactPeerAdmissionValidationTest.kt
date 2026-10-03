package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactPeerAdmissionValidationTest {
    private val fixture = ImpactPeerFixture()
    private val admission = fixture.admission
    private val values = fixture.values

    @Test
    fun `completed peer receipt rejects work time and selected allowance contradictions before publication`() {
        assertEquals(Refinement.Refined(Unit), validate(admission))
        assertCause(
            ImpactPeerProofFailureDocument.WORK_RECEIPT_EXCEEDS_GRANT,
            admission.copy(acquisition = admission.acquisition.copy(examinedWorkUnits = values.count(11))),
        )
        assertCause(
            ImpactPeerProofFailureDocument.TIME_RECEIPT_EXCEEDS_GRANT,
            admission.copy(acquisition = admission.acquisition.copy(elapsedNanos = values.count(100_000_001))),
        )
        assertCause(
            ImpactPeerProofFailureDocument.SITE_WORK_EXCEEDS_ACQUISITION_WORK,
            admission.copy(selection = admission.selection.copy(examinedWorkUnits = values.count(5))),
        )
        assertCause(
            ImpactPeerProofFailureDocument.SITE_GRANT_EXCEEDS_ACQUISITION_GRANT,
            admission.copy(
                acquisition =
                    admission.acquisition.copy(
                        budget =
                            admission.acquisition.budget.copy(
                                maxResults =
                                    (io.github.amichne.kast.kernel.ResultLimit.parse(1) as Refinement.Refined).value
                            )
                    )
            ),
        )
    }

    @Test
    fun `completed peer basis remains distinct from source and exact target claim`() {
        assertCause(
            ImpactPeerProofFailureDocument.TARGET_BASIS_MISMATCH,
            admission.copy(acquisition = admission.acquisition.copy(completedBasis = fixture.source.enclosing.basis)),
        )
        assertEquals(
            Refinement.Rejected(ImpactPeerProofFailureDocument.SAME_SOURCE_ROOT),
            admission.validatePeerAdmission(fixture.peerBasis, fixture.target),
        )
        val role =
            ImpactValueRoleDocument.Argument(
                ImpactInvocationReferenceDocument(fixture.target.range, fixture.source.enclosing),
                values.offset(0),
            )
        assertEquals(
            Refinement.Rejected(ImpactPeerProofFailureDocument.TARGET_BASIS_MISMATCH),
            admission.validatePeerAdmission(fixture.source.enclosing.basis, fixture.target.copy(role = role)),
        )
    }

    private fun assertCause(cause: ImpactPeerProofFailureDocument, candidate: ImpactPeerSiteAdmissionDocument) =
        assertEquals(Refinement.Rejected(cause), validate(candidate))

    private fun validate(candidate: ImpactPeerSiteAdmissionDocument) =
        candidate.validatePeerAdmission(fixture.source.enclosing.basis, fixture.target)
}
