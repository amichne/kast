package io.github.amichne.kast.change.apply.service

import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyCommand
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyCommandFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyExecutor
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyPreconditionFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyRecoveryFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyResult
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyUncertainFailure
import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.ClosedAddDeclarationApply
import io.github.amichne.kast.change.contract.ClosedAddDeclarationApplyFailure
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationApplyJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.ApplyAdmittedAddDeclaration
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyFailure
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyFailure
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.JournaledAddDeclarationRecovery
import io.github.amichne.kast.kernel.Refinement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AddDeclarationPreApplyFailure {
    data class BeginAdmission(
        val failure: BeginAddDeclarationApplyFailure,
    ) : AddDeclarationPreApplyFailure

    data class BeginPersistence(
        val failure: AddDeclarationPlanJournalFailure,
    ) : AddDeclarationPreApplyFailure
}

sealed interface AddDeclarationRecoveryRequiredFailure {
    sealed interface BeforeMutation : AddDeclarationRecoveryRequiredFailure

    data class PhysicalBeforeMutation(
        val failure: AddDeclarationApplyPreconditionFailure,
    ) : BeforeMutation

    data class CommandAdmission(
        val failure: AddDeclarationApplyCommandFailure,
    ) : BeforeMutation

    sealed interface UncertainMutation : AddDeclarationRecoveryRequiredFailure

    data class PhysicalOutcomeUnknown(
        val failure: AddDeclarationApplyUncertainFailure,
    ) : UncertainMutation

    data object ExecutorProtocolFailure : UncertainMutation

    sealed interface AfterMutation : AddDeclarationRecoveryRequiredFailure

    data class PhysicalAfterMutation(
        val failure: AddDeclarationApplyRecoveryFailure,
    ) : AfterMutation

    data class Closure(
        val failure: ClosedAddDeclarationApplyFailure,
    ) : AfterMutation

    data class CompletionAdmission(
        val failure: CompleteAddDeclarationApplyFailure,
    ) : AfterMutation

    data class CompletionPersistence(
        val failure: AddDeclarationPlanJournalFailure,
    ) : AfterMutation

    data class CompletionReceiptMismatch(
        val failure: AppliedUnverifiedReceiptFailure,
    ) : AfterMutation
}

enum class AppliedUnverifiedReceiptFailure {
    PLAN_MISMATCH,
    POSTIMAGE_MISMATCH,
    WRITE_SET_MISMATCH,
}

sealed interface ApplyRecoveryPreparedAddDeclarationResult {
    @ConsistentCopyVisibility
    data class AppliedUnverified private constructor(
        val record: AppliedUnverifiedAddDeclaration,
        val closure: ClosedAddDeclarationApply,
    ) : ApplyRecoveryPreparedAddDeclarationResult {
        companion object {
            internal fun fromClosed(
                record: AppliedUnverifiedAddDeclaration,
                closure: ClosedAddDeclarationApply,
            ): Refinement<AppliedUnverified, AppliedUnverifiedReceiptFailure> = when {
                record.plan != closure.plan ->
                    Refinement.Rejected(AppliedUnverifiedReceiptFailure.PLAN_MISMATCH)
                record.afterImage != closure.observation.afterImage ->
                    Refinement.Rejected(AppliedUnverifiedReceiptFailure.POSTIMAGE_MISMATCH)
                record.observedWriteSet != closure.observedWriteSet ->
                    Refinement.Rejected(AppliedUnverifiedReceiptFailure.WRITE_SET_MISMATCH)
                else -> Refinement.Refined(AppliedUnverified(record, closure))
            }
        }
    }

    data class RejectedBeforeAdmission(
        val failure: AddDeclarationPreApplyFailure,
    ) : ApplyRecoveryPreparedAddDeclarationResult {
        val mutationProgress: AddDeclarationMutationProgress = AddDeclarationMutationProgress.NOT_BEGUN
    }

    @ConsistentCopyVisibility
    data class ApplyAdmissionReconciliationRequired private constructor(
        val recoveryPrepared: JournaledAddDeclarationRecovery,
    ) : ApplyRecoveryPreparedAddDeclarationResult {
        val planId: AddDeclarationPlanId = recoveryPrepared.revalidated.plan.planId
        val durableProgress: AddDeclarationMutationProgress =
            AddDeclarationMutationProgress.MAY_HAVE_BEGUN

        companion object {
            internal fun from(
                recoveryPrepared: JournaledAddDeclarationRecovery,
            ): ApplyAdmissionReconciliationRequired =
                ApplyAdmissionReconciliationRequired(recoveryPrepared)
        }
    }

    @ConsistentCopyVisibility
    data class RecoveryRequiredBeforeMutation private constructor(
        val admitted: ApplyAdmittedAddDeclaration,
        val failure: AddDeclarationRecoveryRequiredFailure.BeforeMutation,
    ) : ApplyRecoveryPreparedAddDeclarationResult {
        val physicalProgress: AddDeclarationMutationProgress = AddDeclarationMutationProgress.NOT_BEGUN

        companion object {
            internal fun from(
                admitted: ApplyAdmittedAddDeclaration,
                failure: AddDeclarationRecoveryRequiredFailure.BeforeMutation,
            ): RecoveryRequiredBeforeMutation = RecoveryRequiredBeforeMutation(admitted, failure)
        }
    }

    @ConsistentCopyVisibility
    data class RecoveryRequiredAfterMutation private constructor(
        val admitted: ApplyAdmittedAddDeclaration,
        val failure: AddDeclarationRecoveryRequiredFailure.AfterMutation,
    ) : ApplyRecoveryPreparedAddDeclarationResult {
        val physicalProgress: AddDeclarationMutationProgress = AddDeclarationMutationProgress.BEGUN

        companion object {
            internal fun from(
                admitted: ApplyAdmittedAddDeclaration,
                failure: AddDeclarationRecoveryRequiredFailure.AfterMutation,
            ): RecoveryRequiredAfterMutation = RecoveryRequiredAfterMutation(admitted, failure)
        }
    }

    @ConsistentCopyVisibility
    data class RecoveryRequiredMutationOutcomeUnknown private constructor(
        val admitted: ApplyAdmittedAddDeclaration,
        val failure: AddDeclarationRecoveryRequiredFailure.UncertainMutation,
    ) : ApplyRecoveryPreparedAddDeclarationResult {
        val physicalProgress: AddDeclarationMutationProgress =
            AddDeclarationMutationProgress.MAY_HAVE_BEGUN

        companion object {
            internal fun from(
                admitted: ApplyAdmittedAddDeclaration,
                failure: AddDeclarationRecoveryRequiredFailure.UncertainMutation,
            ): RecoveryRequiredMutationOutcomeUnknown =
                RecoveryRequiredMutationOutcomeUnknown(admitted, failure)
        }
    }

    @ConsistentCopyVisibility
    data class CompletionReconciliationRequired private constructor(
        val admitted: ApplyAdmittedAddDeclaration,
        val closure: ClosedAddDeclarationApply,
    ) : ApplyRecoveryPreparedAddDeclarationResult {
        val planId: AddDeclarationPlanId = admitted.plan.planId
        val physicalProgress: AddDeclarationMutationProgress = AddDeclarationMutationProgress.BEGUN

        companion object {
            internal fun from(
                admitted: ApplyAdmittedAddDeclaration,
                closure: ClosedAddDeclarationApply,
            ): CompletionReconciliationRequired =
                CompletionReconciliationRequired(admitted, closure)
        }
    }
}

class AddDeclarationApplicationService(
    private val journal: AddDeclarationApplyJournal,
    private val executor: AddDeclarationApplyExecutor,
) {
    /**
     * Proof transition:
     * `JournaledAddDeclarationRecovery -> ApplyRecoveryPreparedAddDeclarationResult`.
     *
     * Success establishes a durable applied-unverified state after exact physical closure.
     * Expected failures before v3 are closed by `AddDeclarationPreApplyFailure`; every outcome
     * after v3 that is not success carries `ApplyAdmittedAddDeclaration` recovery authority and
     * exact physical progress. Raw effects remain confined to the injected journal and executor.
     */
    suspend fun apply(
        recovery: JournaledAddDeclarationRecovery,
    ): ApplyRecoveryPreparedAddDeclarationResult = gateFor(recovery.revalidated.plan).withLock {
        val begin = when (val result = BeginAddDeclarationApply.admit(recovery.record)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@withLock rejected(
                AddDeclarationPreApplyFailure.BeginAdmission(result.failure),
            )
        }
        val admitted = when (val result = journal.beginApply(begin)) {
            is BeginAddDeclarationApplyResult.Begun -> result.record
            is BeginAddDeclarationApplyResult.Rejected -> return@withLock rejected(
                AddDeclarationPreApplyFailure.BeginPersistence(result.failure),
            )
            is BeginAddDeclarationApplyResult.CommitOutcomeUnknown -> return@withLock (
                ApplyRecoveryPreparedAddDeclarationResult.ApplyAdmissionReconciliationRequired.from(
                    recovery,
                )
                                                                                      )
        }
        val command = when (val result = AddDeclarationApplyCommand.fromPreparedRecovery(
            recovery.prepared,
            admitted,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@withLock recoveryBeforeMutation(
                admitted,
                AddDeclarationRecoveryRequiredFailure.CommandAdmission(result.failure),
            )
        }
        val execution = try {
            executor.apply(command)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return@withLock recoveryUncertain(
                admitted,
                AddDeclarationRecoveryRequiredFailure.ExecutorProtocolFailure,
            )
        }
        val observation = when (val result = execution) {
            is AddDeclarationApplyResult.Applied -> result.observation
            is AddDeclarationApplyResult.RejectedBeforeMutation -> return@withLock recoveryBeforeMutation(
                admitted = admitted,
                failure = AddDeclarationRecoveryRequiredFailure.PhysicalBeforeMutation(result.failure),
            )
            is AddDeclarationApplyResult.MutationOutcomeUnknown -> return@withLock recoveryUncertain(
                admitted = admitted,
                failure = AddDeclarationRecoveryRequiredFailure.PhysicalOutcomeUnknown(result.failure),
            )
            is AddDeclarationApplyResult.RecoveryRequiredAfterMutation -> return@withLock recoveryAfterMutation(
                admitted = admitted,
                failure = AddDeclarationRecoveryRequiredFailure.PhysicalAfterMutation(result.failure),
            )
        }
        val closed = when (val result = ClosedAddDeclarationApply.prove(admitted.plan, observation)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@withLock recoveryAfterMutation(
                admitted,
                AddDeclarationRecoveryRequiredFailure.Closure(result.failure),
            )
        }
        val complete = when (val result = CompleteAddDeclarationApply.admit(
            admitted = admitted,
            closure = closed,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@withLock recoveryAfterMutation(
                admitted,
                AddDeclarationRecoveryRequiredFailure.CompletionAdmission(result.failure),
            )
        }
        when (val result = journal.completeApply(complete)) {
            is CompleteAddDeclarationApplyResult.Completed -> when (val receipt =
                ApplyRecoveryPreparedAddDeclarationResult.AppliedUnverified.fromClosed(
                    result.record,
                    closed,
                )) {
                is Refinement.Refined -> receipt.value
                is Refinement.Rejected -> recoveryAfterMutation(
                    admitted,
                    AddDeclarationRecoveryRequiredFailure.CompletionReceiptMismatch(receipt.failure),
                )
            }
            is CompleteAddDeclarationApplyResult.Rejected -> recoveryAfterMutation(
                admitted,
                AddDeclarationRecoveryRequiredFailure.CompletionPersistence(result.failure),
            )
            is CompleteAddDeclarationApplyResult.CommitOutcomeUnknown ->
                ApplyRecoveryPreparedAddDeclarationResult.CompletionReconciliationRequired.from(
                    admitted,
                    closed,
                )
        }
    }

    private fun rejected(
        failure: AddDeclarationPreApplyFailure,
    ): ApplyRecoveryPreparedAddDeclarationResult.RejectedBeforeAdmission =
        ApplyRecoveryPreparedAddDeclarationResult.RejectedBeforeAdmission(failure)

    private fun recoveryBeforeMutation(
        admitted: ApplyAdmittedAddDeclaration,
        failure: AddDeclarationRecoveryRequiredFailure.BeforeMutation,
    ): ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation =
        ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation.from(admitted, failure)

    private fun recoveryAfterMutation(
        admitted: ApplyAdmittedAddDeclaration,
        failure: AddDeclarationRecoveryRequiredFailure.AfterMutation,
    ): ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredAfterMutation =
        ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredAfterMutation.from(admitted, failure)

    private fun recoveryUncertain(
        admitted: ApplyAdmittedAddDeclaration,
        failure: AddDeclarationRecoveryRequiredFailure.UncertainMutation,
    ): ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredMutationOutcomeUnknown =
        ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredMutationOutcomeUnknown.from(
            admitted,
            failure,
        )

    companion object {
        private val gates = ConcurrentHashMap<String, Mutex>()

        private fun gateFor(plan: PlannedAddDeclaration): Mutex =
            gates.computeIfAbsent(plan.intent.workspaceRoot.value) { Mutex() }
    }
}
