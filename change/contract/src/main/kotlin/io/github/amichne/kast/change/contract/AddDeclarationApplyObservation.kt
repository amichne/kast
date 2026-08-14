package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

@JvmInline
value class AddDeclarationChangedDocumentPath private constructor(val value: String) :
    Comparable<AddDeclarationChangedDocumentPath> {
    override fun compareTo(other: AddDeclarationChangedDocumentPath): Int = value.compareTo(other.value)

    companion object {
        /**
         * Proof transition:
         * `String -> Refinement<AddDeclarationChangedDocumentPath,
         * AddDeclarationChangedDocumentPathFailure>`.
         *
         * Establishes a canonical absolute changed-document identity. The closed expected failure
         * is `AddDeclarationChangedDocumentPathFailure`; raw paths may be extracted only from the
         * physical document observer.
         */
        fun parse(
            raw: String,
        ): Refinement<AddDeclarationChangedDocumentPath, AddDeclarationChangedDocumentPathFailure> {
            val parsed = try {
                Path.of(raw)
            } catch (_: Exception) {
                return Refinement.Rejected(AddDeclarationChangedDocumentPathFailure.INVALID)
            }
            if (!parsed.isAbsolute || parsed.normalize().toString() != raw) {
                return Refinement.Rejected(AddDeclarationChangedDocumentPathFailure.INVALID)
            }
            return Refinement.Refined(AddDeclarationChangedDocumentPath(raw))
        }
    }
}

enum class AddDeclarationChangedDocumentPathFailure {
    INVALID,
}

enum class AddDeclarationUndoAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class AddDeclarationApplyObservationFailure {
    NO_CHANGED_DOCUMENT,
    CHANGED_DOCUMENT_PATH_INVALID,
}

@ConsistentCopyVisibility
data class AddDeclarationApplyObservation private constructor(
    val planId: AddDeclarationPlanId,
    val changedDocumentPaths: Set<AddDeclarationChangedDocumentPath>,
    val afterImage: ExactFileContentProof,
    val undoAvailability: AddDeclarationUndoAvailability,
    val mutationProgress: AddDeclarationMutationProgress,
) {
    init {
        require(mutationProgress == AddDeclarationMutationProgress.BEGUN)
    }

    companion object {
        /**
         * Proof transition:
         * `PlannedAddDeclaration` plus raw changed-document paths, exact after image, and undo
         * observation to `Refinement<AddDeclarationApplyObservation,
         * AddDeclarationApplyObservationFailure>`.
         *
         * Establishes a non-empty canonical physical observation bound to the exact PlanId and
         * proves source mutation began. The closed expected failure is
         * `AddDeclarationApplyObservationFailure`; raw path and undo extraction is permitted only
         * at the physical adapter boundary.
         */
        fun observe(
            plan: PlannedAddDeclaration,
            changedDocumentPaths: Set<String>,
            afterImage: ExactFileContentProof,
            undoAvailability: AddDeclarationUndoAvailability,
        ): Refinement<AddDeclarationApplyObservation, AddDeclarationApplyObservationFailure> {
            if (changedDocumentPaths.isEmpty()) {
                return Refinement.Rejected(AddDeclarationApplyObservationFailure.NO_CHANGED_DOCUMENT)
            }
            val parsed = buildSet {
                changedDocumentPaths.forEach { raw ->
                    when (val path = AddDeclarationChangedDocumentPath.parse(raw)) {
                        is Refinement.Refined -> add(path.value)
                        is Refinement.Rejected -> return Refinement.Rejected(
                            AddDeclarationApplyObservationFailure.CHANGED_DOCUMENT_PATH_INVALID,
                        )
                    }
                }
            }
            return Refinement.Refined(
                AddDeclarationApplyObservation(
                    planId = plan.planId,
                    changedDocumentPaths = parsed,
                    afterImage = afterImage,
                    undoAvailability = undoAvailability,
                    mutationProgress = AddDeclarationMutationProgress.BEGUN,
                ),
            )
        }
    }
}

enum class ClosedAddDeclarationApplyFailure {
    PLAN_ID_MISMATCH,
    POSTIMAGE_MISMATCH,
    WRITE_SET_MISMATCH,
    UNDO_STATE_UNSUPPORTED,
}

@ConsistentCopyVisibility
data class ClosedAddDeclarationApply private constructor(
    val plan: PlannedAddDeclaration,
    val observation: AddDeclarationApplyObservation,
    val observedWriteSet: DeclaredWriteSet,
) {
    companion object {
        /**
         * Proof transition:
         * planned mutation plus physical observation to
         * `Refinement<ClosedAddDeclarationApply, ClosedAddDeclarationApplyFailure>`.
         *
         * Establishes exact PlanId, approved physical postimage, singleton declared write-set
         * closure, and the pinned headless global-undo state. The closed expected failure is
         * `ClosedAddDeclarationApplyFailure`; raw document identities are consumed only at the
         * apply service boundary.
         */
        fun prove(
            plan: PlannedAddDeclaration,
            observation: AddDeclarationApplyObservation,
        ): Refinement<ClosedAddDeclarationApply, ClosedAddDeclarationApplyFailure> {
            if (observation.planId != plan.planId) {
                return Refinement.Rejected(ClosedAddDeclarationApplyFailure.PLAN_ID_MISMATCH)
            }
            if (observation.afterImage != plan.expectedFile.postimage) {
                return Refinement.Rejected(ClosedAddDeclarationApplyFailure.POSTIMAGE_MISMATCH)
            }
            val actualPaths = observation.changedDocumentPaths.map { it.value }.toSet()
            val expectedPaths = plan.declaredWriteSet.paths.map { it.value }.toSet()
            if (actualPaths != expectedPaths) {
                return Refinement.Rejected(ClosedAddDeclarationApplyFailure.WRITE_SET_MISMATCH)
            }
            if (observation.undoAvailability != AddDeclarationUndoAvailability.UNAVAILABLE) {
                return Refinement.Rejected(ClosedAddDeclarationApplyFailure.UNDO_STATE_UNSUPPORTED)
            }
            return Refinement.Refined(
                ClosedAddDeclarationApply(plan, observation, plan.declaredWriteSet),
            )
        }
    }
}
