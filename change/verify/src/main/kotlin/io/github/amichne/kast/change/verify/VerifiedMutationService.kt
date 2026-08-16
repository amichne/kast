package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease

data class VerifiedMutationRequest(
    val plan: AddDeclarationChangePlan,
    val applied: AppliedUnverified,
)

enum class VerifiedMutationAdmissionFailure {
    PLAN_ID_MISMATCH,
    PRIOR_LEASE_MISMATCH,
    SOURCE_MISMATCH,
}

/** Plan and applied state proven to describe the same exact physical mutation. */
class AdmittedVerifiedMutationRequest private constructor(
    val plan: AddDeclarationChangePlan,
    val applied: AppliedUnverified,
) {
    companion object {
        /**
         * Proof transition: `VerifiedMutationRequest -> Refinement<
         * AdmittedVerifiedMutationRequest, VerifiedMutationAdmissionFailure>`.
         *
         * Establishes exact plan identity, G0 lease, and source identity before any publication
         * effect begins. [VerifiedMutationAdmissionFailure] is the closed expected failure. Raw
         * extraction is prohibited; only typed KCS-015 and KCS-017 proof enters.
         */
        fun admit(
            request: VerifiedMutationRequest,
        ): Refinement<AdmittedVerifiedMutationRequest, VerifiedMutationAdmissionFailure> = when {
            request.plan.planId != request.applied.planId ->
                Refinement.Rejected(VerifiedMutationAdmissionFailure.PLAN_ID_MISMATCH)
            request.plan.sourceSnapshot.lease != request.applied.priorLease ->
                Refinement.Rejected(VerifiedMutationAdmissionFailure.PRIOR_LEASE_MISMATCH)
            request.plan.sourceSnapshot.file != request.applied.source ->
                Refinement.Rejected(VerifiedMutationAdmissionFailure.SOURCE_MISMATCH)
            else -> Refinement.Refined(
                AdmittedVerifiedMutationRequest(request.plan, request.applied),
            )
        }
    }
}

/** Final mutation-success capability; construction requires complete KCS-018 proof. */
class VerifiedReceipt private constructor(
    val verification: CompleteAddDeclarationVerification,
    val obligations: DischargedAddDeclarationObligations,
) {
    val planId: AddDeclarationPlanId
        get() = verification.plan.planId

    val priorLease: SemanticReadLease
        get() = verification.applied.priorLease

    val resultingWorkspace: PublishedWorkspace
        get() = verification.resulting.workspace

    companion object {
        /**
         * Proof transition: `CompleteAddDeclarationVerification -> VerifiedReceipt`.
         *
         * Establishes final success with distinct generation, complete coverage, discharged
         * obligations, clear diagnostics, unchanged existing relations, and accepted semantic
         * delta. There is no expected failure because the input already carries exhaustive proof.
         * Raw extraction is prohibited; persistence or transport may project only typed fields.
         */
        internal fun issue(verification: CompleteAddDeclarationVerification): VerifiedReceipt =
            VerifiedReceipt(
                verification,
                DischargedAddDeclarationObligations.issue(verification),
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
        val rejection: AddDeclarationVerificationObservationRejection,
    ) : VerifiedMutationResult

    data class RejectedAfterObservation(
        val applied: AppliedUnverified,
        val resulting: DistinctResultingWorkspace,
        val evidence: AddDeclarationVerificationEvidence,
        val failures: Set<AddDeclarationProofFailure>,
    ) : VerifiedMutationResult
}

/** Public `change.verify` operation; only [VerifiedReceipt] is final mutation success. */
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
    private val observer: AddDeclarationVerificationObserver,
) : VerifiedMutationOperations {
    override fun verify(request: VerifiedMutationRequest): VerifiedMutationResult {
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
            AddDeclarationVerificationObservationRequest(
                admitted.plan,
                admitted.applied,
                resulting,
            ),
        )) {
            is AddDeclarationVerificationObservation.Observed -> result.evidence
            is AddDeclarationVerificationObservation.Rejected ->
                return VerifiedMutationResult.RejectedAfterResultingWorkspace(
                    admitted.applied,
                    resulting,
                    result.reason,
                )
        }
        return when (val proof = CompleteAddDeclarationVerification.admit(
            admitted.plan,
            admitted.applied,
            resulting,
            evidence,
        )) {
            is Refinement.Refined -> VerifiedMutationResult.Verified(VerifiedReceipt.issue(proof.value))
            is Refinement.Rejected -> VerifiedMutationResult.RejectedAfterObservation(
                admitted.applied,
                resulting,
                evidence,
                proof.failure,
            )
        }
    }
}
