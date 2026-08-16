package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.ReplaceDeclarationObligation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ReplaceDeclarationVerificationTest {
    private val fixture = VerifiedMutationFixture()
    private val plan = fixture.replaceDeclarationPlan()
    private val applied = applyExactMutation(fixture, plan)

    @Test
    fun `exact resulting declaration proof is required for verified receipt`() {
        val result = service(fixture.replaceDeclarationEvidence(applied)).verify(
            fixture.request(plan, applied),
        )

        val verified = assertInstanceOf(VerifiedMutationResult.Verified::class.java, result)
        assertEquals(plan.planId, verified.receipt.planId)
        assertEquals(ReplaceDeclarationObligation.entries, verified.receipt.obligations.values)
    }

    @Test
    fun `different resulting declaration rejects replacement proof`() {
        val result = service(
            fixture.replaceDeclarationEvidence(applied, "fun service(): Int = 2"),
        ).verify(fixture.request(plan, applied))

        val rejected = assertInstanceOf(
            VerifiedMutationResult.RejectedAfterObservation::class.java,
            result,
        )
        assertEquals(
            setOf(ReplaceDeclarationProofFailure.REPLACEMENT_DECLARATION_MISMATCH),
            rejected.failures,
        )
    }

    private fun service(
        evidence: ReplaceDeclarationVerificationEvidence,
    ): VerifiedMutationService = VerifiedMutationService(
        ResultingGenerationPublisher {
            ResultingGenerationPublication.Published(fixture.resultingWorkspace)
        },
        ChangeVerificationObserver {
            ChangeVerificationObservation.Observed(evidence)
        },
    )
}
