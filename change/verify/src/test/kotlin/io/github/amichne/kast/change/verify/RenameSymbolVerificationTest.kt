package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.RenameSymbolObligation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RenameSymbolVerificationTest {
    private val fixture = VerifiedMutationFixture()
    private val plan = fixture.renamePlan()
    private val applied = applyExactMutation(fixture, plan)

    @Test
    fun `exact resulting rename proof is required for verified receipt`() {
        val result = service(fixture.renameEvidence(applied)).verify(
            fixture.request(plan, applied),
        )

        val verified = assertInstanceOf(VerifiedMutationResult.Verified::class.java, result)
        assertEquals(plan.planId, verified.receipt.planId)
        assertEquals(RenameSymbolObligation.entries, verified.receipt.obligations.values)
    }

    @Test
    fun `old declaration remaining rejects resulting rename proof`() {
        val complete = fixture.renameEvidence(applied)
        val rejectedDelta = ObservedRenameSymbolDelta.fromCompilerBoundary(
            complete.observedDelta.oldName,
            complete.observedDelta.newName,
            1,
            1,
            0,
            1,
        ).refined()

        val result = service(complete.copy(observedDelta = rejectedDelta)).verify(
            fixture.request(plan, applied),
        )

        val rejected = assertInstanceOf(
            VerifiedMutationResult.RejectedAfterObservation::class.java,
            result,
        )
        assertEquals(setOf(RenameSymbolProofFailure.OLD_DECLARATION_REMAINS), rejected.failures)
    }

    private fun service(evidence: RenameSymbolVerificationEvidence): VerifiedMutationService =
        VerifiedMutationService(
            ResultingGenerationPublisher {
                ResultingGenerationPublication.Published(fixture.resultingWorkspace)
            },
            ChangeVerificationObserver {
                ChangeVerificationObservation.Observed(evidence)
            },
        )
}
