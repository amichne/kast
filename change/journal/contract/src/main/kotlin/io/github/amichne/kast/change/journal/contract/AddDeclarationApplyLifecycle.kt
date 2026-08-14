package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.ClosedAddDeclarationApply
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.kernel.Refinement

enum class BeginAddDeclarationApplyFailure {
    VERSION_EXHAUSTED,
}

@ConsistentCopyVisibility
data class BeginAddDeclarationApply private constructor(
    val recoveryPrepared: RecoveryPreparedAddDeclaration,
    val nextVersion: AddDeclarationPlanStateVersion,
) {
    val expectedVersion: AddDeclarationPlanStateVersion
        get() = recoveryPrepared.version

    companion object {
        /**
         * Proof transition:
         * `RecoveryPreparedAddDeclaration -> Refinement<BeginAddDeclarationApply,
         * BeginAddDeclarationApplyFailure>`.
         *
         * Establishes an exact recovery-prepared prior state with one finite CAS successor before
         * source mutation may begin. The closed expected failure is
         * `BeginAddDeclarationApplyFailure`; raw versions remain confined to the journal adapter.
         */
        fun admit(
            recoveryPrepared: RecoveryPreparedAddDeclaration,
        ): Refinement<BeginAddDeclarationApply, BeginAddDeclarationApplyFailure> =
            when (val next = recoveryPrepared.version.next()) {
                is Refinement.Refined -> Refinement.Refined(
                    BeginAddDeclarationApply(recoveryPrepared, next.value),
                )
                is Refinement.Rejected ->
                    Refinement.Rejected(BeginAddDeclarationApplyFailure.VERSION_EXHAUSTED)
            }
    }
}

enum class ApplyAdmittedAddDeclarationRestoreFailure {
    PRIOR_VERSION_MISMATCH,
    CURRENT_VERSION_INVALID,
    MUTATION_PROGRESS_INVALID,
}

@ConsistentCopyVisibility
data class ApplyAdmittedAddDeclaration private constructor(
    override val plan: PlannedAddDeclaration,
    override val version: AddDeclarationPlanStateVersion,
    val priorStage: AddDeclarationPlanStage,
    val priorVersion: AddDeclarationPlanStateVersion,
    val recoveryPrepared: RecoveryPreparedAddDeclaration,
    val mutationProgress: AddDeclarationMutationProgress,
) : PersistedAddDeclarationPlan {
    override val stage: AddDeclarationPlanStage = AddDeclarationPlanStage.APPLY_ADMITTED

    companion object {
        /**
         * Proof transition:
         * `BeginAddDeclarationApply -> Refinement<ApplyAdmittedAddDeclaration,
         * BeginAddDeclarationApplyFailure>`.
         *
         * Establishes the conservative durable point after which recovery is mandatory for every
         * non-success outcome. The closed expected failure is `BeginAddDeclarationApplyFailure`;
         * the journal adapter is the outer boundary permitted to persist raw lifecycle fields.
         */
        fun begin(
            command: BeginAddDeclarationApply,
        ): Refinement<ApplyAdmittedAddDeclaration, BeginAddDeclarationApplyFailure> {
            val prior = command.recoveryPrepared
            return Refinement.Refined(
                ApplyAdmittedAddDeclaration(
                    plan = prior.plan,
                    version = command.nextVersion,
                    priorStage = prior.stage,
                    priorVersion = prior.version,
                    recoveryPrepared = prior,
                    mutationProgress = AddDeclarationMutationProgress.MAY_HAVE_BEGUN,
                ),
            )
        }

        /**
         * Proof transition:
         * recovery-prepared state plus stored apply fields to
         * `Refinement<ApplyAdmittedAddDeclaration,
         * ApplyAdmittedAddDeclarationRestoreFailure>`.
         *
         * Replays the exact recovery-prepared-to-applying transition and proves adjacent versions
         * and conservative mutation progress. The closed expected failure is
         * `ApplyAdmittedAddDeclarationRestoreFailure`; raw fields may be extracted only by the
         * journal record decoder.
         */
        fun restore(
            prior: RecoveryPreparedAddDeclaration,
            currentVersion: AddDeclarationPlanStateVersion,
            priorVersion: AddDeclarationPlanStateVersion,
            mutationProgress: AddDeclarationMutationProgress,
        ): Refinement<ApplyAdmittedAddDeclaration, ApplyAdmittedAddDeclarationRestoreFailure> {
            if (priorVersion != prior.version) {
                return Refinement.Rejected(
                    ApplyAdmittedAddDeclarationRestoreFailure.PRIOR_VERSION_MISMATCH,
                )
            }
            val expected = priorVersion.next().valueOrNull()
                           ?: return Refinement.Rejected(
                               ApplyAdmittedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                           )
            if (currentVersion != expected) {
                return Refinement.Rejected(
                    ApplyAdmittedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                )
            }
            if (mutationProgress != AddDeclarationMutationProgress.MAY_HAVE_BEGUN) {
                return Refinement.Rejected(
                    ApplyAdmittedAddDeclarationRestoreFailure.MUTATION_PROGRESS_INVALID,
                )
            }
            return Refinement.Refined(
                ApplyAdmittedAddDeclaration(
                    plan = prior.plan,
                    version = currentVersion,
                    priorStage = prior.stage,
                    priorVersion = priorVersion,
                    recoveryPrepared = prior,
                    mutationProgress = mutationProgress,
                ),
            )
        }
    }
}

enum class CompleteAddDeclarationApplyFailure {
    PLAN_MISMATCH,
    POSTIMAGE_MISMATCH,
    WRITE_SET_MISMATCH,
    VERSION_EXHAUSTED,
}

@ConsistentCopyVisibility
data class CompleteAddDeclarationApply private constructor(
    val admitted: ApplyAdmittedAddDeclaration,
    val closure: ClosedAddDeclarationApply,
    val nextVersion: AddDeclarationPlanStateVersion,
) {
    val afterImage: ExactFileContentProof
        get() = closure.observation.afterImage

    val observedWriteSet: DeclaredWriteSet
        get() = closure.observedWriteSet

    val expectedVersion: AddDeclarationPlanStateVersion
        get() = admitted.version

    companion object {
        /**
         * Proof transition:
         * applying lifecycle plus physically closed observation to
         * `Refinement<CompleteAddDeclarationApply, CompleteAddDeclarationApplyFailure>`.
         *
         * Establishes exact approved postimage and declared write-set closure before the durable
         * applied-unverified CAS. The closed expected failure is
         * `CompleteAddDeclarationApplyFailure`; raw physical observations are admitted only by the
         * apply service boundary.
         */
        fun admit(
            admitted: ApplyAdmittedAddDeclaration,
            closure: ClosedAddDeclarationApply,
        ): Refinement<CompleteAddDeclarationApply, CompleteAddDeclarationApplyFailure> = when {
            closure.plan != admitted.plan ->
                Refinement.Rejected(CompleteAddDeclarationApplyFailure.PLAN_MISMATCH)
            closure.observation.afterImage != admitted.plan.expectedFile.postimage ->
                Refinement.Rejected(CompleteAddDeclarationApplyFailure.POSTIMAGE_MISMATCH)
            closure.observedWriteSet != admitted.plan.declaredWriteSet ->
                Refinement.Rejected(CompleteAddDeclarationApplyFailure.WRITE_SET_MISMATCH)
            else -> when (val next = admitted.version.next()) {
                is Refinement.Refined -> Refinement.Refined(
                    CompleteAddDeclarationApply(admitted, closure, next.value),
                )
                is Refinement.Rejected ->
                    Refinement.Rejected(CompleteAddDeclarationApplyFailure.VERSION_EXHAUSTED)
            }
        }
    }
}

enum class AppliedUnverifiedAddDeclarationRestoreFailure {
    PRIOR_VERSION_MISMATCH,
    CURRENT_VERSION_INVALID,
    POSTIMAGE_MISMATCH,
    WRITE_SET_MISMATCH,
    MUTATION_PROGRESS_INVALID,
}

@ConsistentCopyVisibility
data class AppliedUnverifiedAddDeclaration private constructor(
    override val plan: PlannedAddDeclaration,
    override val version: AddDeclarationPlanStateVersion,
    val priorStage: AddDeclarationPlanStage,
    val priorVersion: AddDeclarationPlanStateVersion,
    val admitted: ApplyAdmittedAddDeclaration,
    val afterImage: ExactFileContentProof,
    val observedWriteSet: DeclaredWriteSet,
    val mutationProgress: AddDeclarationMutationProgress,
) : PersistedAddDeclarationPlan {
    override val stage: AddDeclarationPlanStage = AddDeclarationPlanStage.APPLIED_UNVERIFIED

    companion object {
        /**
         * Proof transition:
         * `CompleteAddDeclarationApply -> Refinement<AppliedUnverifiedAddDeclaration,
         * CompleteAddDeclarationApplyFailure>`.
         *
         * Establishes one adjacent durable applied-unverified state with exact postimage and write
         * closure. The closed expected failure is `CompleteAddDeclarationApplyFailure`; raw
         * lifecycle fields remain confined to the journal adapter.
         */
        fun complete(
            command: CompleteAddDeclarationApply,
        ): Refinement<AppliedUnverifiedAddDeclaration, CompleteAddDeclarationApplyFailure> {
            val prior = command.admitted
            return Refinement.Refined(
                AppliedUnverifiedAddDeclaration(
                    plan = prior.plan,
                    version = command.nextVersion,
                    priorStage = prior.stage,
                    priorVersion = prior.version,
                    admitted = prior,
                    afterImage = command.afterImage,
                    observedWriteSet = command.observedWriteSet,
                    mutationProgress = AddDeclarationMutationProgress.BEGUN,
                ),
            )
        }

        /**
         * Proof transition:
         * applying state plus stored completion fields to
         * `Refinement<AppliedUnverifiedAddDeclaration,
         * AppliedUnverifiedAddDeclarationRestoreFailure>`.
         *
         * Replays the exact applying-to-applied-unverified transition and proves adjacent versions,
         * exact postimage, closed write set, and begun progress. The closed expected failure is
         * `AppliedUnverifiedAddDeclarationRestoreFailure`; raw fields may be extracted only by the
         * journal record decoder.
         */
        fun restore(
            prior: ApplyAdmittedAddDeclaration,
            currentVersion: AddDeclarationPlanStateVersion,
            priorVersion: AddDeclarationPlanStateVersion,
            afterImage: ExactFileContentProof,
            observedWriteSet: DeclaredWriteSet,
            mutationProgress: AddDeclarationMutationProgress,
        ): Refinement<AppliedUnverifiedAddDeclaration, AppliedUnverifiedAddDeclarationRestoreFailure> {
            if (priorVersion != prior.version) {
                return Refinement.Rejected(
                    AppliedUnverifiedAddDeclarationRestoreFailure.PRIOR_VERSION_MISMATCH,
                )
            }
            val expected = priorVersion.next().valueOrNull()
                           ?: return Refinement.Rejected(
                               AppliedUnverifiedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                           )
            if (currentVersion != expected) {
                return Refinement.Rejected(
                    AppliedUnverifiedAddDeclarationRestoreFailure.CURRENT_VERSION_INVALID,
                )
            }
            if (afterImage != prior.plan.expectedFile.postimage) {
                return Refinement.Rejected(
                    AppliedUnverifiedAddDeclarationRestoreFailure.POSTIMAGE_MISMATCH,
                )
            }
            if (observedWriteSet != prior.plan.declaredWriteSet) {
                return Refinement.Rejected(
                    AppliedUnverifiedAddDeclarationRestoreFailure.WRITE_SET_MISMATCH,
                )
            }
            if (mutationProgress != AddDeclarationMutationProgress.BEGUN) {
                return Refinement.Rejected(
                    AppliedUnverifiedAddDeclarationRestoreFailure.MUTATION_PROGRESS_INVALID,
                )
            }
            return Refinement.Refined(
                AppliedUnverifiedAddDeclaration(
                    plan = prior.plan,
                    version = currentVersion,
                    priorStage = prior.stage,
                    priorVersion = priorVersion,
                    admitted = prior,
                    afterImage = afterImage,
                    observedWriteSet = observedWriteSet,
                    mutationProgress = mutationProgress,
                ),
            )
        }
    }
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
