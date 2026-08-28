package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryPreparation
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.recovery.RecordAppliedAddDeclarationResult
import io.github.amichne.kast.change.verify.ChangeApplicationIssuance
import io.github.amichne.kast.change.verify.ChangeApplicationLookup
import io.github.amichne.kast.change.verify.ChangePlanIssuance
import io.github.amichne.kast.change.verify.ChangePlanLookup
import io.github.amichne.kast.change.verify.DurableChangeAuthorityFailure
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.sqlite.SqliteDurableChangeAuthority
import io.github.amichne.kast.evidence.sqlite.SqliteDurableChangeAuthorityOpenResult
import io.github.amichne.kast.evidence.sqlite.HostedDurableMutationAudit
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournal
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournalOpenResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedChangeAuthorityRestartTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `plan and application identities survive authority reopen`() {
        val fixture = AddDeclarationPlanFixture()
        val plan = when (val planned = PureAddDeclarationPlanningService().plan(fixture.request())) {
            is AddDeclarationPlanResult.Planned -> planned.plan
            is AddDeclarationPlanResult.Rejected -> error(planned.failure.toString())
        }
        val state = HostedWorkspaceStateLocation.locate(
            KastUserStateRoot.parse(temporary.toString()).refined(),
            plan.priorLease.workspaceRoot,
        ).refined()
        val first = authority(state)
        val planIdentity = (first.issuePlan(plan) as ChangePlanIssuance.Issued).identity

        val journal = journal(state)
        val recovery = AddDeclarationRecoveryService(journal)
        val preparation = AddDeclarationRecoveryPreparation.fromPlan(
            plan,
            RecoveryPreimage.fromBoundary(fixture.sourcePreimage),
        ).refined()
        val prepared = (recovery.prepare(preparation) as PrepareAddDeclarationRecoveryResult.Prepared)
            .recovery
        val appliedRecovery = (
            recovery.recordApplied(prepared) as RecordAppliedAddDeclarationResult.Recorded
            ).recovery
        val postimage = WorkspaceSourceContentHash.parse(sha256("changed".toByteArray())).refined()
        val applied = AppliedUnverified.restore(
            plan,
            postimage,
            appliedRecovery.record.binding,
        ).refined()
        assertInstanceOf(
            HostedDurableMutationAudit.RecoveryRequired::class.java,
            authority(state).auditMutationState(),
        )
        val applicationIdentity = (
            first.issueApplication(plan, applied) as ChangeApplicationIssuance.Issued
            ).identity

        val reopened = authority(state)
        assertEquals(HostedDurableMutationAudit.Clean, reopened.auditMutationState())
        val loadedPlan = reopened.loadPlan(planIdentity)
        assertInstanceOf(ChangePlanLookup.Found::class.java, loadedPlan)
        assertEquals(plan.planId, (loadedPlan as ChangePlanLookup.Found).plan.planId)
        val loadedApplication = reopened.loadApplication(applicationIdentity)
        assertInstanceOf(ChangeApplicationLookup.Found::class.java, loadedApplication)
        val pending = (loadedApplication as ChangeApplicationLookup.Found).application
        assertEquals(plan.planId, pending.plan.planId)
        assertEquals(postimage, pending.application.postimage)
    }

    @Test
    fun `identities are root isolated and corrupt documents fail closed after reopen`() {
        val fixture = AddDeclarationPlanFixture()
        val plan = when (val planned = PureAddDeclarationPlanningService().plan(fixture.request())) {
            is AddDeclarationPlanResult.Planned -> planned.plan
            is AddDeclarationPlanResult.Rejected -> error(planned.failure.toString())
        }
        val userStateRoot = KastUserStateRoot.parse(temporary.toString()).refined()
        val state = HostedWorkspaceStateLocation.locate(
            userStateRoot,
            plan.priorLease.workspaceRoot,
        ).refined()
        val authority = authority(state)
        val identity = (authority.issuePlan(plan) as ChangePlanIssuance.Issued).identity
        val otherRoot = CanonicalWorkspaceRoot.fromCanonicalPath(
            Path.of("/different-workspace"),
        ).refined()
        val otherState = HostedWorkspaceStateLocation.locate(userStateRoot, otherRoot).refined()

        assertEquals(ChangePlanLookup.Missing, authority(otherState).loadPlan(identity))

        DriverManager.getConnection(
            "jdbc:sqlite:${state.mutationDatabase.valueAtSqliteBoundary()}",
        ).use { connection ->
            connection.prepareStatement(
                "UPDATE hosted_change_plan SET document = ? WHERE identity = ?",
            ).use { statement ->
                statement.setString(1, "{}")
                statement.setString(2, identity.value)
                statement.executeUpdate()
            }
        }
        val corrupted = authority(state).loadPlan(identity)
        assertInstanceOf(ChangePlanLookup.Rejected::class.java, corrupted)
        assertEquals(
            DurableChangeAuthorityFailure.CORRUPT_RECORD,
            (corrupted as ChangePlanLookup.Rejected).failure,
        )
    }

    private fun authority(
        state: HostedWorkspaceStateLocation,
    ): SqliteDurableChangeAuthority = when (
        val opened = SqliteDurableChangeAuthority.open(state.mutationDatabase)
    ) {
        is SqliteDurableChangeAuthorityOpenResult.Opened -> opened.authority
        is SqliteDurableChangeAuthorityOpenResult.Rejected -> error(opened.failure.toString())
    }

    private fun journal(
        state: HostedWorkspaceStateLocation,
    ): SqliteMutationRecoveryJournal = when (
        val opened = SqliteMutationRecoveryJournal.open(state.mutationDatabase)
    ) {
        is SqliteMutationRecoveryJournalOpenResult.Opened -> opened.journal
        is SqliteMutationRecoveryJournalOpenResult.Rejected -> error(opened.failure.toString())
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
