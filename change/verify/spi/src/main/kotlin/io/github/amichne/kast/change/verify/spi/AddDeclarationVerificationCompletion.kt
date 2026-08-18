package io.github.amichne.kast.change.verify.spi

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStateVersion
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.VerifiedAddDeclaration
import io.github.amichne.kast.kernel.Refinement

sealed interface CompleteAddDeclarationVerificationFailure {
    data object VerificationPlanMismatch : CompleteAddDeclarationVerificationFailure

    data object VersionExhausted : CompleteAddDeclarationVerificationFailure
}

/**
 * Terminal-completion capability issued only from an exact scoped verification observation.
 */
@ConsistentCopyVisibility
data class CompleteAddDeclarationVerification private constructor(
    val applied: AppliedUnverifiedAddDeclaration,
    val verification: ObservedAddDeclarationVerification,
    val nextVersion: AddDeclarationPlanStateVersion,
) {
    val expectedVersion: AddDeclarationPlanStateVersion
        get() = applied.version

    companion object {
        /**
         * Proof transition: applied-unverified state plus exact scoped compiler observation to
         * `Refinement<CompleteAddDeclarationVerification,
         * CompleteAddDeclarationVerificationFailure>`.
         *
         * Establishes that the non-publicly issued observation belongs to the exact applied plan,
         * carries the full newer publication and observed identity into its durable projection, and
         * has one finite v5 successor. The closed expected failure is
         * [CompleteAddDeclarationVerificationFailure]. Raw extraction remains confined to the
         * journal persistence boundary.
         */
        fun admit(
            applied: AppliedUnverifiedAddDeclaration,
            verification: ObservedAddDeclarationVerification,
        ): Refinement<CompleteAddDeclarationVerification, CompleteAddDeclarationVerificationFailure> {
            if (verification.command.plan != applied.plan) {
                return Refinement.Rejected(
                    CompleteAddDeclarationVerificationFailure.VerificationPlanMismatch,
                )
            }
            val nextVersion = when (val next = applied.version.next()) {
                is Refinement.Refined -> next.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    CompleteAddDeclarationVerificationFailure.VersionExhausted,
                )
            }
            return Refinement.Refined(
                CompleteAddDeclarationVerification(
                    applied = applied,
                    verification = verification,
                    nextVersion = nextVersion,
                ),
            )
        }
    }
}

sealed interface CompleteAddDeclarationVerificationResult {
    data class Completed(
        val record: VerifiedAddDeclaration,
    ) : CompleteAddDeclarationVerificationResult

    data class Rejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : CompleteAddDeclarationVerificationResult

    data class CommitOutcomeUnknown(
        val planId: AddDeclarationPlanId,
    ) : CompleteAddDeclarationVerificationResult
}

/** Narrow durable capability for only the terminal v4-to-v5 journal transition. */
interface AddDeclarationVerificationJournal {
    fun completeVerification(
        command: CompleteAddDeclarationVerification,
    ): CompleteAddDeclarationVerificationResult
}
