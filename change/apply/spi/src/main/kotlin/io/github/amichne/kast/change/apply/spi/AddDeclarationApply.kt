package io.github.amichne.kast.change.apply.spi

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationApplyObservation
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.ApplyAdmittedAddDeclaration
import io.github.amichne.kast.change.recovery.contract.PreparedAddDeclarationRecovery
import io.github.amichne.kast.kernel.Refinement

/**
 * Physical command backed by exact KIP-033 revalidation and durable recovery.
 *
 * A bare plan cannot construct this command. The retained [PreparedAddDeclarationRecovery]
 * preserves the exact physical recovery proof through the executor boundary. The application
 * service separately establishes the durable write-ahead journal state before invoking the port.
 */
@ConsistentCopyVisibility
data class AddDeclarationApplyCommand private constructor(
    val preparedRecovery: PreparedAddDeclarationRecovery,
    val admitted: ApplyAdmittedAddDeclaration,
) {
    val plan: PlannedAddDeclaration
        get() = preparedRecovery.revalidated.plan

    companion object {
        /**
         * Proof transition: prepared physical recovery plus durable apply admission to
         * `Refinement<AddDeclarationApplyCommand, AddDeclarationApplyCommandFailure>`.
         *
         * Preserves exact KIP-033 revalidation, physically durable recovery, PlanId,
         * preimage/postimage, declaration, generation, singleton write set, and write-ahead
         * admission. The closed expected failure is [AddDeclarationApplyCommandFailure].
         */
        fun fromPreparedRecovery(
            preparedRecovery: PreparedAddDeclarationRecovery,
            admitted: ApplyAdmittedAddDeclaration,
        ): Refinement<AddDeclarationApplyCommand, AddDeclarationApplyCommandFailure> =
            if (
                preparedRecovery.revalidated.plan != admitted.plan ||
                preparedRecovery.durableRecovery.material != admitted.recoveryPrepared.recovery
            ) {
                Refinement.Rejected(AddDeclarationApplyCommandFailure.ADMISSION_MISMATCH)
            } else {
                Refinement.Refined(AddDeclarationApplyCommand(preparedRecovery, admitted))
            }
    }
}

enum class AddDeclarationApplyCommandFailure {
    ADMISSION_MISMATCH,
}

enum class AddDeclarationApplyPreconditionFailure {
    CANCELLED,
    UNSUPPORTED_RUNTIME,
    DUMB_MODE,
    TARGET_NOT_FOUND,
    TARGET_NOT_KOTLIN,
    TARGET_READ_ONLY,
    TARGET_PREIMAGE_MISMATCH,
    DECLARATION_INVALID,
    TARGET_DOCUMENT_UNAVAILABLE,
    TARGET_BYTES_UNAVAILABLE,
    TARGET_INVALIDATED,
    APPROVED_POSTIMAGE_UNREPRESENTABLE,
    WRITE_COMMAND_NOT_ENTERED,
}

enum class AddDeclarationApplyUncertainFailure {
    WRITE_COMMAND_FAILED,
}

enum class AddDeclarationApplyRecoveryFailure {
    WRITE_COMMAND_FAILED,
    DOCUMENT_SAVE_INCOMPLETE,
    OBSERVATION_INVALID,
}

sealed interface AddDeclarationApplyResult {
    data class Applied(
        val observation: AddDeclarationApplyObservation,
    ) : AddDeclarationApplyResult

    data class RejectedBeforeMutation(
        val failure: AddDeclarationApplyPreconditionFailure,
    ) : AddDeclarationApplyResult {
        val mutationProgress: AddDeclarationMutationProgress = AddDeclarationMutationProgress.NOT_BEGUN
    }

    data class MutationOutcomeUnknown(
        val failure: AddDeclarationApplyUncertainFailure,
    ) : AddDeclarationApplyResult {
        val mutationProgress: AddDeclarationMutationProgress =
            AddDeclarationMutationProgress.MAY_HAVE_BEGUN
    }

    data class RecoveryRequiredAfterMutation(
        val failure: AddDeclarationApplyRecoveryFailure,
    ) : AddDeclarationApplyResult {
        val mutationProgress: AddDeclarationMutationProgress = AddDeclarationMutationProgress.BEGUN
    }
}

fun interface AddDeclarationApplyExecutor {
    /**
     * Proof transition: `AddDeclarationApplyCommand -> AddDeclarationApplyResult`.
     *
     * Applied carries exact after-image, write observation, and undo evidence. Expected failures
     * are separated by construction into definitely-before-mutation and recovery-required cases.
     * The command preserves both physical recovery and durable apply-admitted proofs.
     */
    suspend fun apply(command: AddDeclarationApplyCommand): AddDeclarationApplyResult
}
