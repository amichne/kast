package io.github.amichne.kast.change.plan.service

import io.github.amichne.kast.change.contract.AddDeclarationIntent
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanner
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningRejection
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningResult

sealed interface PlanAndPersistAddDeclarationResult {
    data class Stored(
        val record: PersistedAddDeclarationPlan.AwaitingApproval,
    ) : PlanAndPersistAddDeclarationResult

    data class Existing(
        val record: PersistedAddDeclarationPlan,
    ) : PlanAndPersistAddDeclarationResult

    data class PlanningRejected(
        val rejection: AddDeclarationPlanningRejection,
    ) : PlanAndPersistAddDeclarationResult

    data class JournalRejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : PlanAndPersistAddDeclarationResult
}

sealed interface PersistAddDeclarationPlanResult {
    data class Stored(
        val record: PersistedAddDeclarationPlan.AwaitingApproval,
    ) : PersistAddDeclarationPlanResult

    data class Existing(
        val record: PersistedAddDeclarationPlan,
    ) : PersistAddDeclarationPlanResult

    data class JournalRejected(
        val failure: AddDeclarationPlanJournalFailure,
    ) : PersistAddDeclarationPlanResult
}

interface AddDeclarationPlanPersistence {
    /**
     * Proof transition: `PlannedAddDeclaration -> PersistAddDeclarationPlanResult`.
     *
     * A stored or existing result establishes that the detached canonical plan crossed a durable
     * journal boundary. Expected failure is closed by `JournalRejected`; callers must not retain
     * an enclosing semantic read capability while invoking this transition.
     */
    fun persist(plan: PlannedAddDeclaration): PersistAddDeclarationPlanResult

    data object Unavailable : AddDeclarationPlanPersistence {
        override fun persist(plan: PlannedAddDeclaration): PersistAddDeclarationPlanResult =
            PersistAddDeclarationPlanResult.JournalRejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )
    }
}

class AddDeclarationPlanPersistenceService(
    private val journal: AddDeclarationPlanJournal,
) : AddDeclarationPlanPersistence {
    /**
     * Proof transition: `AddDeclarationIntent -> PlanAndPersistAddDeclarationResult`.
     *
     * A stored or existing result establishes a durable canonical plan only after the planner has
     * returned detached evidence and released its live read resources. Expected failure is closed
     * by `PlanningRejected` or `JournalRejected`; raw plan bytes are never extracted here.
     */
    suspend fun planAndPersist(
        planner: AddDeclarationPlanner,
        intent: AddDeclarationIntent,
    ): PlanAndPersistAddDeclarationResult = when (val planned = planner.plan(intent)) {
        is AddDeclarationPlanningResult.Rejected ->
            PlanAndPersistAddDeclarationResult.PlanningRejected(planned.rejection)
        is AddDeclarationPlanningResult.Planned -> when (val persisted = persist(planned.plan)) {
            is PersistAddDeclarationPlanResult.Stored ->
                PlanAndPersistAddDeclarationResult.Stored(persisted.record)
            is PersistAddDeclarationPlanResult.Existing ->
                PlanAndPersistAddDeclarationResult.Existing(persisted.record)
            is PersistAddDeclarationPlanResult.JournalRejected ->
                PlanAndPersistAddDeclarationResult.JournalRejected(persisted.failure)
        }
    }

    /**
     * Proof transition: `PlannedAddDeclaration -> PersistAddDeclarationPlanResult`.
     *
     * A stored or existing result establishes that the already-detached canonical plan has crossed
     * the durable journal boundary. Expected failure is closed by `JournalRejected`; callers must
     * invoke this only after any enclosing semantic read lease has been validated and released.
     */
    override fun persist(
        plan: PlannedAddDeclaration,
    ): PersistAddDeclarationPlanResult = when (val stored = journal.store(plan)) {
        is StoreAddDeclarationPlanResult.Stored ->
            PersistAddDeclarationPlanResult.Stored(stored.record)
        is StoreAddDeclarationPlanResult.Existing ->
            PersistAddDeclarationPlanResult.Existing(stored.record)
        is StoreAddDeclarationPlanResult.Rejected ->
            PersistAddDeclarationPlanResult.JournalRejected(stored.failure)
    }

    /**
     * Proof transition: `ApproveAddDeclarationPlan -> ApproveAddDeclarationPlanResult`.
     *
     * Delegates one already-refined PlanId, exact-prior-version, explicit-evidence command to the
     * durable journal. Expected failures remain the journal's closed result; no wait or live
     * resource is retained by this service.
     */
    fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
        journal.approve(command)
}
