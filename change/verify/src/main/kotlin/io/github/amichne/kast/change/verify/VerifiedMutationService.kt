package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddFileChangePlan
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.ChangePlanId
import io.github.amichne.kast.change.contract.RenameSymbolChangePlan
import io.github.amichne.kast.change.contract.ReplaceDeclarationChangePlan
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastChangeVerificationOutcome
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.util.concurrent.CancellationException

data class VerifiedMutationRequest(
    val plan: ChangePlan,
    val applied: AppliedUnverified,
)

enum class VerifiedMutationAdmissionFailure {
    PLAN_ID_MISMATCH,
    PRIOR_LEASE_MISMATCH,
    SOURCE_MISMATCH,
}

/** Plan and applied state proven to describe the same exact physical mutation. */
class AdmittedVerifiedMutationRequest private constructor(
    val plan: ChangePlan,
    val applied: AppliedUnverified,
) {
    companion object {
        /**
         * Proof transition: `VerifiedMutationRequest -> Refinement<
         * AdmittedVerifiedMutationRequest, VerifiedMutationAdmissionFailure>`.
         *
         * Establishes exact plan identity, the retained plan-to-application publication proof,
         * and source identity before any publication effect begins.
         * [VerifiedMutationAdmissionFailure] is the closed expected failure. Raw extraction is
         * prohibited; only typed KCS-015 and KCS-017 proof enters.
         */
        fun admit(
            request: VerifiedMutationRequest,
        ): Refinement<AdmittedVerifiedMutationRequest, VerifiedMutationAdmissionFailure> = when {
            request.plan.planId != request.applied.planId ->
                Refinement.Rejected(VerifiedMutationAdmissionFailure.PLAN_ID_MISMATCH)
            request.plan.priorLease != request.applied.publication.plannedLease ||
                request.plan.workspaceState != request.applied.publication.plannedState ||
                request.plan.writes.entries.singleOrNull()?.sourceRoot !=
                request.applied.publication.sourceRoot ||
                request.plan.writes.entries.singleOrNull()?.precondition !=
                request.applied.publication.precondition ->
                Refinement.Rejected(VerifiedMutationAdmissionFailure.PRIOR_LEASE_MISMATCH)
            request.plan.writes.entries.singleOrNull()?.source != request.applied.source ->
                Refinement.Rejected(VerifiedMutationAdmissionFailure.SOURCE_MISMATCH)
            else -> Refinement.Refined(
                AdmittedVerifiedMutationRequest(request.plan, request.applied),
            )
        }
    }
}

/** Final mutation-success capability; construction requires complete KCS-018 proof. */
class VerifiedReceipt private constructor(
    val verification: CompleteChangeVerification,
    val obligations: DischargedChangeObligations,
) {
    val planId: ChangePlanId
        get() = verification.plan.planId

    val priorLease: SemanticReadLease
        get() = verification.applied.priorLease

    val resultingWorkspace: PublishedWorkspace
        get() = verification.resulting.workspace

    companion object {
        /**
         * Proof transition: `CompleteChangeVerification -> VerifiedReceipt`.
         *
         * Establishes final success with distinct generation, complete coverage, discharged
         * obligations, clear diagnostics, unchanged existing relations, and accepted semantic
         * delta. There is no expected failure because the input already carries exhaustive proof.
         * Raw extraction is prohibited; persistence or transport may project only typed fields.
         */
        internal fun issue(verification: CompleteChangeVerification): VerifiedReceipt =
            VerifiedReceipt(
                verification,
                DischargedChangeObligations.issue(verification),
            )
    }
}

sealed interface VerifiedMutationBeforePublicationFailure {
    data class Admission(
        val failure: VerifiedMutationAdmissionFailure,
    ) : VerifiedMutationBeforePublicationFailure

    data class Publication(
        val rejection: ResultingGenerationPublicationRejection,
    ) : VerifiedMutationBeforePublicationFailure
}

sealed interface VerifiedMutationResult {
    data class Verified(
        val receipt: VerifiedReceipt,
    ) : VerifiedMutationResult

    data class RejectedBeforePublication(
        val applied: AppliedUnverified,
        val failure: VerifiedMutationBeforePublicationFailure,
    ) : VerifiedMutationResult

    data class RejectedAfterPublication(
        val applied: AppliedUnverified,
        val published: PublishedWorkspace,
        val failure: DistinctResultingWorkspaceFailure,
    ) : VerifiedMutationResult

    data class RejectedAfterResultingWorkspace(
        val applied: AppliedUnverified,
        val resulting: DistinctResultingWorkspace,
        val rejection: ChangeVerificationObservationRejection,
    ) : VerifiedMutationResult

    data class RejectedAfterObservation(
        val applied: AppliedUnverified,
        val resulting: DistinctResultingWorkspace,
        val evidence: ChangeVerificationEvidence,
        val failures: Set<ChangeProofFailure>,
    ) : VerifiedMutationResult
}

/** Internal verification capability absorbed by public `change.apply` completion. */
fun interface VerifiedMutationOperations {
    /**
     * Proof transition: `VerifiedMutationRequest -> VerifiedMutationResult`.
     *
     * Verified establishes a distinct complete publication and exhaustive semantic proof.
     * Expected admission, publication, observation, and proof failures are closed by
     * [VerifiedMutationResult], retaining the strongest state reached. Platform effects remain in
     * the injected ports.
     */
    fun verify(request: VerifiedMutationRequest): VerifiedMutationResult
}

class VerifiedMutationService(
    private val publisher: ResultingGenerationPublisher,
    private val observer: ChangeVerificationObserver,
    private val observability: KastObservability = KastObservability.Disabled,
) : VerifiedMutationOperations {
    override fun verify(request: VerifiedMutationRequest): VerifiedMutationResult {
        val result = try {
            verifyObserved(request)
        } catch (cancelled: CancellationException) {
            observability.observeChangeVerification(KastChangeVerificationOutcome.INTERRUPTED)
            throw cancelled
        }
        observability.observeChangeVerification(result.observation())
        return result
    }

    private fun verifyObserved(request: VerifiedMutationRequest): VerifiedMutationResult {
        val admitted = when (val result = AdmittedVerifiedMutationRequest.admit(request)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return VerifiedMutationResult.RejectedBeforePublication(
                request.applied,
                VerifiedMutationBeforePublicationFailure.Admission(result.failure),
            )
        }
        val published = when (val result = publisher.publishAfter(admitted.applied.priorLease)) {
            is ResultingGenerationPublication.Published -> result.workspace
            is ResultingGenerationPublication.Rejected ->
                return VerifiedMutationResult.RejectedBeforePublication(
                    admitted.applied,
                    VerifiedMutationBeforePublicationFailure.Publication(result.reason),
                )
        }
        val resulting = when (val result = DistinctResultingWorkspace.admit(
            admitted.applied.priorLease,
            published,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return VerifiedMutationResult.RejectedAfterPublication(
                admitted.applied,
                published,
                result.failure,
            )
        }
        val evidence = when (val result = observer.observe(
            ChangeVerificationObservationRequest(
                admitted.plan,
                admitted.applied,
                resulting,
            ),
        )) {
            is ChangeVerificationObservation.Observed -> result.evidence
            is ChangeVerificationObservation.Rejected ->
                return VerifiedMutationResult.RejectedAfterResultingWorkspace(
                    admitted.applied,
                    resulting,
                    result.reason,
                )
        }
        return when (val proof = complete(admitted, resulting, evidence)) {
            is Refinement.Refined -> VerifiedMutationResult.Verified(VerifiedReceipt.issue(proof.value))
            is Refinement.Rejected -> VerifiedMutationResult.RejectedAfterObservation(
                admitted.applied,
                resulting,
                evidence,
                proof.failure,
            )
        }
    }

    private fun VerifiedMutationResult.observation(): KastChangeVerificationOutcome = when (this) {
        is VerifiedMutationResult.Verified -> KastChangeVerificationOutcome.VERIFIED
        is VerifiedMutationResult.RejectedBeforePublication -> when (val reason = failure) {
            is VerifiedMutationBeforePublicationFailure.Admission -> KastChangeVerificationOutcome.ADMISSION_REJECTED
            is VerifiedMutationBeforePublicationFailure.Publication -> when (reason.rejection) {
                ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE ->
                    KastChangeVerificationOutcome.CURRENT_PUBLICATION_UNAVAILABLE
                ResultingGenerationPublicationRejection.RECONCILIATION_INVALIDATED ->
                    KastChangeVerificationOutcome.RECONCILIATION_INVALIDATED
                ResultingGenerationPublicationRejection.RECONCILIATION_BLOCKED ->
                    KastChangeVerificationOutcome.RECONCILIATION_BLOCKED
                ResultingGenerationPublicationRejection.PUBLICATION_PROTOCOL_REJECTED ->
                    KastChangeVerificationOutcome.PUBLICATION_PROTOCOL_REJECTED
            }
        }
        is VerifiedMutationResult.RejectedAfterPublication -> KastChangeVerificationOutcome.RESULTING_PUBLICATION_REJECTED
        is VerifiedMutationResult.RejectedAfterResultingWorkspace -> when (rejection) {
            ChangeVerificationObservationRejection.RESULTING_SEMANTIC_STATE_UNAVAILABLE ->
                KastChangeVerificationOutcome.RESULTING_SEMANTIC_STATE_UNAVAILABLE
            ChangeVerificationObservationRejection.RESULTING_GENERATION_MOVED ->
                KastChangeVerificationOutcome.RESULTING_GENERATION_MOVED
            ChangeVerificationObservationRejection.COMPILER_OBSERVATION_REJECTED ->
                KastChangeVerificationOutcome.COMPILER_OBSERVATION_REJECTED
        }
        is VerifiedMutationResult.RejectedAfterObservation -> KastChangeVerificationOutcome.SEMANTIC_PROOF_REJECTED
    }

    private fun complete(
        admitted: AdmittedVerifiedMutationRequest,
        resulting: DistinctResultingWorkspace,
        evidence: ChangeVerificationEvidence,
    ): Refinement<CompleteChangeVerification, Set<ChangeProofFailure>> = when (
        val plan = admitted.plan
    ) {
        is AddFileChangePlan -> when (evidence) {
            is AddFileVerificationEvidence -> when (val proof = CompleteAddFileVerification.admit(
                plan,
                admitted.applied,
                resulting,
                evidence,
            )) {
                is Refinement.Refined -> Refinement.Refined(proof.value)
                is Refinement.Rejected -> Refinement.Rejected(proof.failure)
            }
            else -> evidenceMismatch()
        }
        is AddDeclarationChangePlan -> when (evidence) {
            is AddDeclarationVerificationEvidence -> when (val proof =
                CompleteAddDeclarationVerification.admit(
                    plan,
                    admitted.applied,
                    resulting,
                    evidence,
                )
            ) {
                is Refinement.Refined -> Refinement.Refined(proof.value)
                is Refinement.Rejected -> Refinement.Rejected(proof.failure)
            }
            else -> evidenceMismatch()
        }
        is RenameSymbolChangePlan -> when (evidence) {
            is RenameSymbolVerificationEvidence -> when (val proof =
                CompleteRenameSymbolVerification.admit(
                    plan,
                    admitted.applied,
                    resulting,
                    evidence,
                )
            ) {
                is Refinement.Refined -> Refinement.Refined(proof.value)
                is Refinement.Rejected -> Refinement.Rejected(proof.failure)
            }
            else -> evidenceMismatch()
        }
        is ReplaceDeclarationChangePlan -> when (evidence) {
            is ReplaceDeclarationVerificationEvidence -> when (val proof =
                CompleteReplaceDeclarationVerification.admit(
                    plan,
                    admitted.applied,
                    resulting,
                    evidence,
                )
            ) {
                is Refinement.Refined -> Refinement.Refined(proof.value)
                is Refinement.Rejected -> Refinement.Rejected(proof.failure)
            }
            else -> evidenceMismatch()
        }
    }

    private fun evidenceMismatch(): Refinement.Rejected<Set<ChangeProofFailure>> =
        Refinement.Rejected(setOf(ChangeProofProtocolFailure.EVIDENCE_INTENT_MISMATCH))
}

enum class ChangeProofProtocolFailure : ChangeProofFailure {
    EVIDENCE_INTENT_MISMATCH,
}
