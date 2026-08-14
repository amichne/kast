package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.AddDeclarationSha256
import io.github.amichne.kast.change.contract.AddDeclarationTargetPath
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration

enum class VerifiedAddDeclarationSourceRangeFailure {
    INVALID,
}

@ConsistentCopyVisibility
data class VerifiedAddDeclarationSourceRange private constructor(
    val startOffset: Int,
    val endOffset: Int,
) {
    companion object {
        /**
         * Proof transition: raw persisted offsets to
         * `Refinement<VerifiedAddDeclarationSourceRange,
         * VerifiedAddDeclarationSourceRangeFailure>`.
         *
         * Establishes a non-empty, non-negative observed declaration range. The closed expected
         * failure is [VerifiedAddDeclarationSourceRangeFailure]; raw offsets may enter only from
         * the verification projection or journal decoder boundary.
         */
        fun admit(
            startOffset: Int,
            endOffset: Int,
        ): Refinement<VerifiedAddDeclarationSourceRange, VerifiedAddDeclarationSourceRangeFailure> =
            if (startOffset < 0 || endOffset <= startOffset) {
                Refinement.Rejected(VerifiedAddDeclarationSourceRangeFailure.INVALID)
            } else {
                Refinement.Refined(VerifiedAddDeclarationSourceRange(startOffset, endOffset))
            }
    }
}

/** Durable typed projection of the exact declaration identity observed by verification. */
@ConsistentCopyVisibility
data class VerifiedAddDeclarationObservedIdentity internal constructor(
    val targetPath: AddDeclarationTargetPath,
    val sourceRange: VerifiedAddDeclarationSourceRange,
    val packageName: String,
    val declarationName: String,
    val declarationKind: AddDeclarationKind,
)

/**
 * Detached durable proof of one exact verification observation and its published generation.
 */
@ConsistentCopyVisibility
data class VerifiedAddDeclarationReceipt internal constructor(
    val planId: AddDeclarationPlanId,
    val publication: PublishedWorkspaceGeneration,
    val identity: VerifiedAddDeclarationObservedIdentity,
    val postimageSha256: AddDeclarationSha256,
)

enum class VerifiedAddDeclarationRestoreFailure {
    PRIOR_VERSION_MISMATCH,
    CURRENT_VERSION_INVALID,
    RESULT_GENERATION_NOT_ADVANCED,
    TARGET_MISMATCH,
    SOURCE_RANGE_INVALID,
    SEMANTIC_DELTA_INVALID,
    SEMANTIC_DELTA_MISMATCH,
    POSTIMAGE_MISMATCH,
}

/** Terminal v5 lifecycle state with no recovery or physical-apply capability. */
@ConsistentCopyVisibility
data class VerifiedAddDeclaration private constructor(
    override val plan: PlannedAddDeclaration,
    override val version: AddDeclarationPlanStateVersion,
    val priorStage: AddDeclarationPlanStage,
    val priorVersion: AddDeclarationPlanStateVersion,
    val receipt: VerifiedAddDeclarationReceipt,
) : PersistedAddDeclarationPlan {
    override val stage: AddDeclarationPlanStage = AddDeclarationPlanStage.VERIFIED

    companion object {
        /**
         * Proof transition: exact applied parent plus stored durable receipt fields to
         * `Refinement<VerifiedAddDeclaration, VerifiedAddDeclarationRestoreFailure>`.
         *
         * Replays the exact v4-to-v5 transition and admits the only durable receipt constructor by
         * proving lifecycle adjacency, newer full publication, approved postimage, and matched
         * target/range/package/name/kind identity. The closed expected failure is
         * [VerifiedAddDeclarationRestoreFailure]. Raw SQLite values may be extracted only by the
         * journal decoder before this transition.
         */
        fun restore(
            prior: AppliedUnverifiedAddDeclaration,
            currentVersion: AddDeclarationPlanStateVersion,
            priorVersion: AddDeclarationPlanStateVersion,
            publication: PublishedWorkspaceGeneration,
            targetPath: AddDeclarationTargetPath,
            observedStartOffset: Int,
            observedEndOffset: Int,
            observedPackageName: String,
            observedDeclarationName: String,
            observedDeclarationKind: AddDeclarationKind,
            postimageSha256: AddDeclarationSha256,
        ): Refinement<VerifiedAddDeclaration, VerifiedAddDeclarationRestoreFailure> {
            if (priorVersion != prior.version) {
                return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.PRIOR_VERSION_MISMATCH,
                )
            }
            val expectedVersion = when (val next = priorVersion.next()) {
                is Refinement.Refined -> next.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                )
            }
            if (currentVersion != expectedVersion) {
                return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                )
            }
            if (publication.generation.value <= prior.plan.generation.value) {
                return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.RESULT_GENERATION_NOT_ADVANCED,
                )
            }
            if (targetPath != prior.plan.target.targetPath) {
                return Refinement.Rejected(VerifiedAddDeclarationRestoreFailure.TARGET_MISMATCH)
            }
            val range = when (val admitted = VerifiedAddDeclarationSourceRange.admit(
                observedStartOffset,
                observedEndOffset,
            )) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.SOURCE_RANGE_INVALID,
                )
            }
            val delta = when (val admitted = ExpectedAddDeclarationDelta.admit(
                packageName = observedPackageName,
                declarationName = observedDeclarationName,
                declarationKind = observedDeclarationKind,
            )) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.SEMANTIC_DELTA_INVALID,
                )
            }
            if (delta != prior.plan.expectedSemanticDelta) {
                return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.SEMANTIC_DELTA_MISMATCH,
                )
            }
            if (postimageSha256 != prior.afterImage.sha256) {
                return Refinement.Rejected(
                    VerifiedAddDeclarationRestoreFailure.POSTIMAGE_MISMATCH,
                )
            }
            val receipt = VerifiedAddDeclarationReceipt(
                planId = prior.plan.planId,
                publication = publication,
                identity = VerifiedAddDeclarationObservedIdentity(
                    targetPath = targetPath,
                    sourceRange = range,
                    packageName = delta.packageName,
                    declarationName = delta.declarationName,
                    declarationKind = delta.declarationKind,
                ),
                postimageSha256 = postimageSha256,
            )
            return Refinement.Refined(
                VerifiedAddDeclaration(
                    plan = prior.plan,
                    version = currentVersion,
                    priorStage = prior.stage,
                    priorVersion = priorVersion,
                    receipt = receipt,
                ),
            )
        }
    }
}
