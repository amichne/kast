package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class VerifiedMutationTest {
    private val fixture = VerifiedMutationFixture()

    @Test
    fun `complete resulting proof is the only path to verified receipt`() {
        val publisher = FixedResultingGenerationPublisher(
            ResultingGenerationPublication.Published(fixture.resultingWorkspace),
        )
        val observer = FixedVerificationObserver(
            ChangeVerificationObservation.Observed(fixture.completeEvidence()),
        )

        val result = service(publisher, observer).verify(fixture.request())

        val verified = assertInstanceOf(VerifiedMutationResult.Verified::class.java, result)
        assertEquals(fixture.plan.planId, verified.receipt.planId)
        assertEquals(fixture.workspace.readLease, verified.receipt.priorLease)
        assertEquals(fixture.resultingWorkspace, verified.receipt.resultingWorkspace)
        assertEquals(AddDeclarationObligation.entries, verified.receipt.obligations.values)
        assertEquals(
            AddDeclarationObligationProofBasis.entries.toSet(),
            verified.receipt.obligations.proofs.mapTo(linkedSetOf()) { it.basis },
        )
        assertEquals(1, publisher.calls)
        assertEquals(1, observer.calls)
    }

    @Test
    fun `same generation cannot reach semantic observation or success`() {
        val publisher = FixedResultingGenerationPublisher(
            ResultingGenerationPublication.Published(fixture.workspace),
        )
        val observer = FixedVerificationObserver(
            ChangeVerificationObservation.Observed(fixture.completeEvidence()),
        )

        val result = service(publisher, observer).verify(fixture.request())

        val rejected = assertInstanceOf(
            VerifiedMutationResult.RejectedAfterPublication::class.java,
            result,
        )
        assertEquals(
            DistinctResultingWorkspaceFailure.GENERATION_NOT_NEWER,
            rejected.failure,
        )
        assertEquals(0, observer.calls)
    }

    @Test
    fun `compiler collision cannot become an observed declaration delta`() {
        val result = ObservedAddDeclarationDelta.fromCompilerBoundary(
            "sample",
            "added",
            AddDeclarationKind.FUNCTION,
            2,
        )

        val rejected = assertInstanceOf(Refinement.Rejected::class.java, result)
        assertEquals(ObservedAddDeclarationDeltaFailure.DECLARATION_AMBIGUOUS, rejected.failure)
    }

    @Test
    fun `incomplete relation coverage leaves obligations unmet`() {
        val evidence = fixture.completeEvidence().copy(
            relations = listOf(fixture.qualifiedResultingRelation()),
        )

        val result = service(evidence).verify(fixture.request())

        assertProofFailure(result, AddDeclarationProofFailure.RELATION_EVIDENCE_INCOMPLETE)
    }

    @Test
    fun `incomplete diagnostics and compiler errors cannot be clear`() {
        val incomplete = fixture.completeEvidence().copy(
            diagnostics = listOf(fixture.qualifiedResultingDiagnostics()),
        )
        val errored = fixture.completeEvidence().copy(
            diagnostics = listOf(fixture.erroredResultingDiagnostics()),
        )

        assertProofFailure(
            service(incomplete).verify(fixture.request()),
            AddDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE,
        )
        assertProofFailure(
            service(errored).verify(fixture.request()),
            AddDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED,
        )
    }

    @Test
    fun `diagnostics outside the exact mutation source scope cannot discharge the plan`() {
        val evidence = fixture.completeEvidence().copy(
            diagnostics = listOf(fixture.expandedResultingDiagnostics()),
        )

        val result = service(evidence).verify(fixture.request())

        assertProofFailure(result, AddDeclarationProofFailure.DIAGNOSTIC_SCOPE_MISMATCH)
    }

    @Test
    fun `unexpected semantic delta cannot discharge the plan`() {
        val evidence = fixture.completeEvidence().copy(
            observedDelta = fixture.observedDelta(
                packageName = "sample",
                declarationName = "other",
                kind = AddDeclarationKind.FUNCTION,
            ),
        )

        val result = service(evidence).verify(fixture.request())

        assertProofFailure(result, AddDeclarationProofFailure.SEMANTIC_DELTA_REJECTED)
    }

    @Test
    fun `changed existing relation semantics cannot be accepted delta`() {
        val evidence = fixture.completeEvidence().copy(
            relations = listOf(fixture.changedResultingRelation()),
        )

        val result = service(evidence).verify(fixture.request())

        assertProofFailure(result, AddDeclarationProofFailure.RELATION_DELTA_REJECTED)
    }

    private fun service(
        evidence: AddDeclarationVerificationEvidence,
    ): VerifiedMutationService = service(
        FixedResultingGenerationPublisher(
            ResultingGenerationPublication.Published(fixture.resultingWorkspace),
        ),
        FixedVerificationObserver(ChangeVerificationObservation.Observed(evidence)),
    )

    private fun service(
        publisher: ResultingGenerationPublisher,
        observer: AddDeclarationVerificationObserver,
    ): VerifiedMutationService = VerifiedMutationService(publisher, observer)

    private fun assertProofFailure(
        result: VerifiedMutationResult,
        expected: AddDeclarationProofFailure,
    ) {
        val rejected = assertInstanceOf(
            VerifiedMutationResult.RejectedAfterObservation::class.java,
            result,
        )
        assertEquals(setOf(expected), rejected.failures)
    }
}

private class FixedResultingGenerationPublisher(
    private val result: ResultingGenerationPublication,
) : ResultingGenerationPublisher {
    var calls: Int = 0

    override fun publishAfter(
        prior: io.github.amichne.kast.workspace.contract.SemanticReadLease,
    ): ResultingGenerationPublication {
        calls += 1
        return result
    }
}

private class FixedVerificationObserver(
    private val result: ChangeVerificationObservation,
) : ChangeVerificationObserver {
    var calls: Int = 0

    override fun observe(
        request: ChangeVerificationObservationRequest,
    ): ChangeVerificationObservation {
        calls += 1
        return result
    }
}
