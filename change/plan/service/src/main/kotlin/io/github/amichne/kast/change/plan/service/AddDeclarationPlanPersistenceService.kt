package io.github.amichne.kast.change.plan.service

import io.github.amichne.kast.change.contract.AddDeclarationIntent
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

class AddDeclarationPlanPersistenceService(
    private val planner: AddDeclarationPlanner,
    private val journal: AddDeclarationPlanJournal,
) {
    /**
     * Proof transition: `AddDeclarationIntent -> PlanAndPersistAddDeclarationResult`.
     *
     * A stored or existing result establishes a durable canonical plan only after the planner has
     * returned detached evidence and released its live read resources. Expected failure is closed
     * by `PlanningRejected` or `JournalRejected`; raw plan bytes are never extracted here.
     */
    suspend fun planAndPersist(
        intent: AddDeclarationIntent,
    ): PlanAndPersistAddDeclarationResult = when (val planned = planner.plan(intent)) {
        is AddDeclarationPlanningResult.Rejected ->
            PlanAndPersistAddDeclarationResult.PlanningRejected(planned.rejection)
        is AddDeclarationPlanningResult.Planned -> when (val stored = journal.store(planned.plan)) {
            is StoreAddDeclarationPlanResult.Stored ->
                PlanAndPersistAddDeclarationResult.Stored(stored.record)
            is StoreAddDeclarationPlanResult.Existing ->
                PlanAndPersistAddDeclarationResult.Existing(stored.record)
            is StoreAddDeclarationPlanResult.Rejected ->
                PlanAndPersistAddDeclarationResult.JournalRejected(stored.failure)
        }
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
