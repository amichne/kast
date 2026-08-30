package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.MutationPlanPublicationRelationship
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
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
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
        val applicationLease = SemanticReadLease(
            plan.priorLease.workspaceRoot,
            EvidenceGeneration.parse(plan.priorLease.generation.value + 1L).refined(),
        )
        val applicationState = WorkspaceStateIdentity.parse("restart-successor").refined()
        val applied = AppliedUnverified.restore(
            plan,
            applicationLease,
            applicationState,
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
        assertEquals(applicationLease, pending.application.priorLease)
        assertEquals(applicationState, pending.application.publication.applicationState)
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

    @Test
    fun `legacy exact application rows are refined without changing public identity`() {
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
        val recovery = AddDeclarationRecoveryService(journal(state))
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
            plan.priorLease,
            plan.workspaceState,
            postimage,
            appliedRecovery.record.binding,
        ).refined()
        val applicationIdentity = (
            first.issueApplication(plan, applied) as ChangeApplicationIssuance.Issued
            ).identity

        replaceApplicationTableWithLegacyRow(
            state,
            planIdentity.value,
            plan.planId.value,
            postimage.value,
            appliedRecovery.record.binding.value,
            applicationIdentity.value,
        )

        val loaded = authority(state).loadApplication(applicationIdentity)
        val pending = assertInstanceOf(ChangeApplicationLookup.Found::class.java, loaded).application
        assertEquals(applicationIdentity, (
            authority(state).issueApplication(plan, pending.application) as ChangeApplicationIssuance.Issued
            ).identity)
        assertEquals(plan.priorLease, pending.application.priorLease)
        assertEquals(
            MutationPlanPublicationRelationship.EXACT,
            pending.application.publication.relationship,
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

    private fun replaceApplicationTableWithLegacyRow(
        state: HostedWorkspaceStateLocation,
        planIdentity: String,
        planId: String,
        postimage: String,
        recoveryBinding: String,
        applicationIdentity: String,
    ) {
        val digest = sha256(
            canonicalFields(planIdentity, planId, postimage, recoveryBinding).toByteArray(),
        )
        DriverManager.getConnection(
            "jdbc:sqlite:${state.mutationDatabase.valueAtSqliteBoundary()}",
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE hosted_change_application")
                statement.execute(
                    """CREATE TABLE hosted_change_application (
                        identity TEXT PRIMARY KEY NOT NULL,
                        plan_identity TEXT NOT NULL REFERENCES hosted_change_plan(identity),
                        plan_id TEXT NOT NULL,
                        postimage_sha256 TEXT NOT NULL,
                        recovery_binding TEXT NOT NULL,
                        record_digest TEXT NOT NULL,
                        UNIQUE(plan_identity, postimage_sha256)
                    ) WITHOUT ROWID""",
                )
            }
            connection.prepareStatement(
                """INSERT INTO hosted_change_application(
                    identity, plan_identity, plan_id, postimage_sha256,
                    recovery_binding, record_digest
                ) VALUES (?, ?, ?, ?, ?, ?)""",
            ).use { statement ->
                statement.setString(1, applicationIdentity)
                statement.setString(2, planIdentity)
                statement.setString(3, planId)
                statement.setString(4, postimage)
                statement.setString(5, recoveryBinding)
                statement.setString(6, digest)
                statement.executeUpdate()
            }
        }
    }

    private fun canonicalFields(vararg fields: String): String = buildString {
        fields.forEach { field ->
            append(field.toByteArray().size)
            append(':')
            append(field)
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
