package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastChangeVerificationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class VerifiedMutationTest {
    private val fixture = VerifiedMutationFixture()

    @Test
    fun `verification cancellation records interruption and preserves cancellation identity`() {
        val signals = mutableListOf<KastChangeVerificationOutcome>()
        val observability = object : KastObservability by KastObservability.Disabled {
            override fun observeChangeVerification(outcome: KastChangeVerificationOutcome) {
                signals.add(outcome)
            }
        }
        val cancellation = CancellationException("test interruption")
        val service = VerifiedMutationService(
            ResultingGenerationPublisher { throw cancellation },
            ChangeVerificationObserver { error("cancelled publication must not observe compiler state") },
            observability,
        )
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            service.verify(fixture.request())
        })
        assertEquals(listOf(KastChangeVerificationOutcome.INTERRUPTED), signals)
    }

    @Test
    fun `verification records exact finite terminal stage while preserving result`() {
        val signals = mutableListOf<KastChangeVerificationOutcome>()
        val observability = object : KastObservability by KastObservability.Disabled {
            override fun observeChangeVerification(outcome: KastChangeVerificationOutcome) {
                signals.add(outcome)
            }
        }
        val request = fixture.request()
        val publication = ResultingGenerationPublisher {
            ResultingGenerationPublication.Published(fixture.resultingWorkspace)
        }
        val observation = ChangeVerificationObserver {
            ChangeVerificationObservation.Observed(fixture.completeEvidence())
        }
        val verified = VerifiedMutationService(publication, observation, observability).verify(request)
        assertInstanceOf(VerifiedMutationResult.Verified::class.java, verified)
        assertEquals(listOf(KastChangeVerificationOutcome.VERIFIED), signals)

        for (failure in ResultingGenerationPublicationRejection.entries) {
            signals.clear()
            val rejected = VerifiedMutationService(
                ResultingGenerationPublisher { ResultingGenerationPublication.Rejected(failure) },
                observation,
                observability,
            ).verify(request)
            assertInstanceOf(VerifiedMutationResult.RejectedBeforePublication::class.java, rejected)
            assertEquals(listOf(KastChangeVerificationOutcome.valueOf(failure.name)), signals)
        }
        for (failure in ChangeVerificationObservationRejection.entries) {
            signals.clear()
            val rejected = VerifiedMutationService(
                publication,
                ChangeVerificationObserver { ChangeVerificationObservation.Rejected(failure) },
                observability,
            ).verify(request)
            assertInstanceOf(VerifiedMutationResult.RejectedAfterResultingWorkspace::class.java, rejected)
            assertEquals(listOf(KastChangeVerificationOutcome.valueOf(failure.name)), signals)
        }

        signals.clear()
        val admission = VerifiedMutationService(publication, observation, observability)
            .verify(fixture.request(fixture.renamePlan(), fixture.applied))
        assertInstanceOf(VerifiedMutationResult.RejectedBeforePublication::class.java, admission)
        assertEquals(listOf(KastChangeVerificationOutcome.ADMISSION_REJECTED), signals)

        signals.clear()
        val sameGeneration = VerifiedMutationService(
            ResultingGenerationPublisher { ResultingGenerationPublication.Published(fixture.workspace) },
            observation,
            observability,
        ).verify(request)
        assertInstanceOf(VerifiedMutationResult.RejectedAfterPublication::class.java, sameGeneration)
        assertEquals(listOf(KastChangeVerificationOutcome.RESULTING_PUBLICATION_REJECTED), signals)

        signals.clear()
        val incomplete = VerifiedMutationService(
            publication,
            ChangeVerificationObserver {
                ChangeVerificationObservation.Observed(fixture.completeEvidence().copy(
                    relations = listOf(fixture.qualifiedResultingRelation()),
                ))
            },
            observability,
        ).verify(request)
        assertInstanceOf(VerifiedMutationResult.RejectedAfterObservation::class.java, incomplete)
        assertEquals(listOf(KastChangeVerificationOutcome.SEMANTIC_PROOF_REJECTED), signals)
    }

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
