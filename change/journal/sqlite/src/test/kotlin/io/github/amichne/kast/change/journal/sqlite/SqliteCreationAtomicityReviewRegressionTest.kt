package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationClasspathFingerprint
import io.github.amichne.kast.change.contract.AddDeclarationCompilerContextFile
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationOutboundReferenceCount
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationProjectModelFingerprint
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Base64

class SqliteCreationAtomicityReviewRegressionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun reviewRegression_planInsertionAndReloadAreAtomic() {
        val database = tempDir.resolve("plan-storage-atomic.db")
        val plan = plan()
        val observer = LifecycleInterleavingObserver(database, plan.planId.value)

        val stored = open(database, observer).store(plan)

        assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(stored)
        assertFalse(observer.planAdvanced)
    }

    @Test
    fun reviewRegression_recoveryInsertionAndReloadAreAtomic() {
        val database = tempDir.resolve("recovery-storage-atomic.db")
        val plan = plan()
        val journal = open(database)
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(
            journal.store(plan),
        ).record
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(approval(awaiting)),
        ).record
        val observer = LifecycleInterleavingObserver(database, plan.planId.value)

        val prepared = open(database, observer).prepareRecovery(recovery(approved))

        assertInstanceOf<PrepareAddDeclarationRecoveryResult.Prepared>(prepared)
        assertFalse(observer.recoveryAdvanced)
    }

    private fun open(
        database: Path,
        observer: SqliteJournalConnectionObserver = SqliteJournalConnectionObserver.Disabled,
    ): SqliteAddDeclarationPlanJournal =
        assertInstanceOf<SqliteAddDeclarationPlanJournalOpenResult.Opened>(
            SqliteAddDeclarationPlanJournal.open(database, observer),
        ).journal

    private fun approval(
        awaiting: PersistedAddDeclarationPlan.AwaitingApproval,
    ): ApproveAddDeclarationPlan = ApproveAddDeclarationPlan.admit(
        planId = awaiting.plan.planId,
        expectedVersion = awaiting.version,
        evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = awaiting.plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined(),
    ).refined()

    private fun recovery(
        approved: PersistedAddDeclarationPlan.Approved,
    ): PrepareAddDeclarationRecovery = PrepareAddDeclarationRecovery.admit(
        approved = approved,
        revalidated = RevalidatedAddDeclaration.admit(
            approved.plan,
            AddDeclarationRevalidationObservation.observe(
                generation = EvidenceGeneration.parse(approved.plan.generation.value).refined(),
                target = approved.plan.target,
                currentFile = approved.plan.expectedFile.preimage,
                provenance = AddDeclarationSourceProvenance.AUTHORED,
                writability = AddDeclarationTargetWritability.WRITABLE,
            ).refined(),
        ).refined(),
    ).refined()

    private fun plan(): PlannedAddDeclaration {
        val before = "package sample\n".toByteArray()
        val after = "package sample\n\nfun added(): Int = 1\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(before),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()
        val target = AddDeclarationTargetCapability.admit(
            intent,
            AddDeclarationSourceOwner.admit(
                sourceRoot = "$ROOT/indexer/src/main/kotlin",
                ideaModuleName = "kast.indexer.main",
                gradleBuildRoot = ROOT,
                gradleProjectPath = ":indexer",
                sourceSetName = "main",
            ).refined(),
        ).refined()
        val generation = EvidenceGeneration.parse(7).refined()
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent = intent,
                generation = generation,
                target = target,
                expectedFile = ExpectedFileProof.admit(
                    target,
                    ExactFileContentProof.admit(
                        hash(before),
                        Base64.getEncoder().encodeToString(before),
                    ).refined(),
                    ExactFileContentProof.admit(
                        hash(after),
                        Base64.getEncoder().encodeToString(after),
                    ).refined(),
                ).refined(),
                declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
                expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
                    packageName = "sample",
                    declarationName = "added",
                    declarationKind = AddDeclarationKind.FUNCTION,
                ).refined(),
                verification = AddDeclarationVerificationContract.forGeneration(generation),
                compilerContext = ExpectedAddDeclarationCompilerContext.admit(
                    generation = generation,
                    projectModelFingerprint = AddDeclarationProjectModelFingerprint.parse(
                        "3".repeat(64),
                    ).refined(),
                    classpathFingerprint = AddDeclarationClasspathFingerprint.parse(
                        "4".repeat(64),
                    ).refined(),
                    contextFiles = listOf(
                        AddDeclarationCompilerContextFile.admit(TARGET, hash(before)).refined(),
                    ),
                    outboundReferenceCount = AddDeclarationOutboundReferenceCount.parse(0).refined(),
                ).refined(),
                compilerEvidence = DetachedCompilerEvidence.admit("{\"complete\":true}").refined(),
            ).refined(),
        )
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private class LifecycleInterleavingObserver(
        private val database: Path,
        private val planId: String,
    ) : SqliteJournalConnectionObserver {
        var planAdvanced = false
            private set
        var recoveryAdvanced = false
            private set

        override fun opened() = Unit

        override fun closed() = Unit

        override fun afterTransitionWrite(operation: SqliteJournalTransitionOperation) {
            when (operation) {
                SqliteJournalTransitionOperation.PLAN_STORAGE -> advanceVisiblePlan()
                SqliteJournalTransitionOperation.RECOVERY_PREPARATION -> advanceVisibleRecovery()
                SqliteJournalTransitionOperation.APPROVAL -> Unit
            }
        }

        private fun advanceVisiblePlan() {
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                if (!connection.visible("add_declaration_plan")) return
                planAdvanced = connection.prepareStatement(
                    """UPDATE add_declaration_plan SET
                        stage = 'APPROVED', state_version = 1,
                        prior_stage = 'AWAITING_APPROVAL', prior_version = 0,
                        approval_plan_id = plan_id, approval_by = 'agent:other',
                        approval_sha256 = ?
                    WHERE plan_id = ? AND stage = 'AWAITING_APPROVAL'""",
                ).use { statement ->
                    statement.setString(1, "b".repeat(64))
                    statement.setString(2, planId)
                    statement.executeUpdate() == 1
                }
            }
        }

        private fun advanceVisibleRecovery() {
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                if (!connection.visible("add_declaration_recovery")) return
                recoveryAdvanced = connection.prepareStatement(
                    """INSERT INTO add_declaration_apply(
                        plan_id, stage, state_version, prior_stage, prior_version
                    ) VALUES (?, 'APPLY_ADMITTED', 3, 'RECOVERY_PREPARED', 2)""",
                ).use { statement ->
                    statement.setString(1, planId)
                    statement.executeUpdate() == 1
                }
            }
        }

        private fun java.sql.Connection.visible(table: String): Boolean =
            prepareStatement("SELECT 1 FROM $table WHERE plan_id = ?").use { statement ->
                statement.setString(1, planId)
                statement.executeQuery().use { rows -> rows.next() }
            }
    }

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
