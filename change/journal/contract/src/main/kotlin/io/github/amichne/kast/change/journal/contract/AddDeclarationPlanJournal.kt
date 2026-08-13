package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.PlannedAddDeclaration

sealed interface AddDeclarationPlanJournalFailure {
    data object StorageUnavailable : AddDeclarationPlanJournalFailure
    data object CorruptRecord : AddDeclarationPlanJournalFailure

    data class PlanIdCollision(
        val planId: AddDeclarationPlanId,
    ) : AddDeclarationPlanJournalFailure

    data class PlanNotFound(
        val planId: AddDeclarationPlanId,
    ) : AddDeclarationPlanJournalFailure

    data class StateVersionExhausted(
        val planId: AddDeclarationPlanId,
    ) : AddDeclarationPlanJournalFailure

    data class PriorStateMismatch(
        val planId: AddDeclarationPlanId,
        val expectedStage: AddDeclarationPlanStage,
        val expectedVersion: AddDeclarationPlanStateVersion,
        val actualStage: AddDeclarationPlanStage,
        val actualVersion: AddDeclarationPlanStateVersion,
    ) : AddDeclarationPlanJournalFailure
}

sealed interface StoreAddDeclarationPlanResult {
    data class Stored(
        val record: PersistedAddDeclarationPlan.AwaitingApproval,
    ) : StoreAddDeclarationPlanResult

    data class Existing(
        val record: PersistedAddDeclarationPlan,
    ) : StoreAddDeclarationPlanResult

    data class Rejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : StoreAddDeclarationPlanResult
}

sealed interface LoadAddDeclarationPlanResult {
    data class Found(
        val record: PersistedAddDeclarationPlan,
    ) : LoadAddDeclarationPlanResult

    data class NotFound(
        val planId: AddDeclarationPlanId,
    ) : LoadAddDeclarationPlanResult

    data class Rejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : LoadAddDeclarationPlanResult
}

sealed interface ApproveAddDeclarationPlanResult {
    data class Approved(
        val record: PersistedAddDeclarationPlan.Approved,
    ) : ApproveAddDeclarationPlanResult

    data class Rejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : ApproveAddDeclarationPlanResult
}

sealed interface PrepareAddDeclarationRecoveryResult {
    data class Prepared(
        val record: RecoveryPreparedAddDeclaration,
    ) : PrepareAddDeclarationRecoveryResult

    data class Rejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : PrepareAddDeclarationRecoveryResult
}

sealed interface BeginAddDeclarationApplyResult {
    data class Begun(
        val record: ApplyAdmittedAddDeclaration,
    ) : BeginAddDeclarationApplyResult

    data class Rejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : BeginAddDeclarationApplyResult

    data class CommitOutcomeUnknown(
        val planId: AddDeclarationPlanId,
    ) : BeginAddDeclarationApplyResult
}

sealed interface CompleteAddDeclarationApplyResult {
    data class Completed(
        val record: AppliedUnverifiedAddDeclaration,
    ) : CompleteAddDeclarationApplyResult

    data class Rejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : CompleteAddDeclarationApplyResult

    data class CommitOutcomeUnknown(
        val planId: AddDeclarationPlanId,
    ) : CompleteAddDeclarationApplyResult
}

/**
 * Durable evidence port for detached add-declaration plans.
 *
 * Implementations must revalidate canonical plan bytes on reads, use exact prior-stage/version
 * compare-and-set for every transition, and release every connection, transaction, and resource
 * before a method returns. Storage is lifecycle evidence and never current semantic authority.
 */
interface AddDeclarationPlanJournal {
    fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult

    fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult

    fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult

    fun prepareRecovery(
        command: PrepareAddDeclarationRecovery,
    ): PrepareAddDeclarationRecoveryResult
}

/**
 * Durable lifecycle capability for a journal that can admit and close physical apply.
 *
 * This refines [AddDeclarationPlanJournal] without forcing planning- or recovery-only journal
 * consumers to possess source-mutation lifecycle authority.
 */
interface AddDeclarationApplyJournal : AddDeclarationPlanJournal {
    fun beginApply(command: BeginAddDeclarationApply): BeginAddDeclarationApplyResult

    fun completeApply(command: CompleteAddDeclarationApply): CompleteAddDeclarationApplyResult
}
