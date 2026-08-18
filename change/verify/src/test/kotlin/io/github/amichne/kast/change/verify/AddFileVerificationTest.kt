package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddFileObligation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class AddFileVerificationTest {
    private val fixture = VerifiedMutationFixture()
    private val plan = fixture.addFilePlan()
    private val applied = applyExactMutation(fixture, plan)

    @Test
    fun `exact resulting file proof is required for verified receipt`() {
        val result = service(fixture.addFileEvidence(applied)).verify(
            fixture.request(plan, applied),
        )

        val verified = assertInstanceOf(VerifiedMutationResult.Verified::class.java, result)
        assertEquals(plan.planId, verified.receipt.planId)
        assertEquals(AddFileObligation.entries, verified.receipt.obligations.values)
    }

    @Test
    fun `mismatched resulting file identity rejects proof`() {
        val complete = fixture.addFileEvidence(applied)
        val result = service(
            complete.copy(
                observedDelta = ObservedAddFileDelta.fromCompilerBoundary(
                    fixture.plan.target.file,
                    1,
                ).refined()
            ),
        ).verify(fixture.request(plan, applied))

        val rejected = assertInstanceOf(
            VerifiedMutationResult.RejectedAfterObservation::class.java,
            result,
        )
        assertEquals(setOf(AddFileProofFailure.FILE_IDENTITY_MISMATCH), rejected.failures)
    }

    private fun service(evidence: AddFileVerificationEvidence): VerifiedMutationService =
        VerifiedMutationService(
            ResultingGenerationPublisher {
                ResultingGenerationPublication.Published(fixture.resultingWorkspace)
            },
            ChangeVerificationObserver {
                ChangeVerificationObservation.Observed(evidence)
            },
        )
}
