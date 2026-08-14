package io.github.amichne.kast.idea

import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAdmission
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAuthority
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationApplyJournal
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.JournaledAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.kernel.Refinement
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.assertInstanceOf
import java.nio.file.Path

internal val documentedIntellijIdeaRuntime = AddDeclarationIntellijRuntimeAuthority {
    AddDeclarationIntellijRuntimeAdmission.Supported.IntelliJIdea262
}

internal fun approveAddDeclarationPlan(
    journal: AddDeclarationApplyJournal,
    awaiting: PersistedAddDeclarationPlan.AwaitingApproval,
): PersistedAddDeclarationPlan.Approved {
    val evidence = RawAddDeclarationPlanApprovalEvidence(
        planId = awaiting.plan.planId.value,
        approvedBy = "agent:operator",
        evidenceSha256 = "a".repeat(64),
    ).refine().refinedValue()
    val command = ApproveAddDeclarationPlan.admit(
        planId = awaiting.plan.planId,
        expectedVersion = awaiting.version,
        evidence = evidence,
    ).refinedValue()
    return assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
        journal.approve(command),
    ).record
}

internal class CapturingAddDeclarationApplyJournal(
    private val delegate: AddDeclarationApplyJournal,
) : AddDeclarationApplyJournal {
    var storedPlan: PlannedAddDeclaration? = null
        private set

    override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult {
        val result = delegate.store(plan)
        if (result !is StoreAddDeclarationPlanResult.Rejected) storedPlan = plan
        return result
    }

    override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult =
        delegate.load(planId)

    override fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
        delegate.approve(command)

    override fun prepareRecovery(
        command: PrepareAddDeclarationRecovery,
    ): PrepareAddDeclarationRecoveryResult = delegate.prepareRecovery(command)

    override fun beginApply(command: BeginAddDeclarationApply): BeginAddDeclarationApplyResult =
        delegate.beginApply(command)

    override fun completeApply(
        command: CompleteAddDeclarationApply,
    ): CompleteAddDeclarationApplyResult = delegate.completeApply(command)
}

internal data class AddDeclarationApplyIntellijFixture(
    val target: Path,
    val targetFile: KtFile,
    val plan: PlannedAddDeclaration,
    val journal: AddDeclarationApplyJournal,
    val recovery: JournaledAddDeclarationRecovery,
)

private fun <T, F> Refinement<T, F>.refinedValue(): T =
    assertInstanceOf<Refinement.Refined<T>>(this).value
