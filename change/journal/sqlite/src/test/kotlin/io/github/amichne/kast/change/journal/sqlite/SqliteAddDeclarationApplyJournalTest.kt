package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationApplyObservation
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
import io.github.amichne.kast.change.contract.AddDeclarationUndoAvailability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.ClosedAddDeclarationApply
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
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.RecoveryPreparedAddDeclaration
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class SqliteAddDeclarationApplyJournalTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `apply admission and physical closure survive reopen as adjacent states`() {
        val database = tempDir.resolve("apply.db")
        val journal = open(database)
        val prepared = prepared(journal)
        val admitted = assertInstanceOf<BeginAddDeclarationApplyResult.Begun>(
            journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined()),
        ).record
        val closure = closed(admitted.plan)
        val completed = assertInstanceOf<CompleteAddDeclarationApplyResult.Completed>(
            journal.completeApply(CompleteAddDeclarationApply.admit(admitted, closure).refined()),
        ).record

        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            open(database).load(admitted.plan.planId),
        ).record

        assertEquals(completed, reopened)
        assertEquals(admitted.version, completed.priorVersion)
        assertEquals(closure.observation.afterImage, completed.afterImage)
        assertEquals(closure.observedWriteSet, completed.observedWriteSet)
    }

    @Test
    fun `two concurrent apply admissions have exactly one write-ahead winner`() {
        val journal = open(tempDir.resolve("concurrent-apply.db"))
        val prepared = prepared(journal)
        val command = BeginAddDeclarationApply.admit(prepared).refined()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = List(2) {
            executor.submit<BeginAddDeclarationApplyResult> {
                ready.countDown()
                assertTrue(start.await(10, TimeUnit.SECONDS))
                journal.beginApply(command)
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        val results = futures.map { it.get(20, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it is BeginAddDeclarationApplyResult.Begun })
        assertEquals(1, results.count { it is BeginAddDeclarationApplyResult.Rejected })
    }

    @Test
    fun `tampered applied postimage fails closed on reopen`() {
        val database = tempDir.resolve("tampered-apply.db")
        val journal = open(database)
        val prepared = prepared(journal)
        val admitted = assertInstanceOf<BeginAddDeclarationApplyResult.Begun>(
            journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined()),
        ).record
        assertInstanceOf<CompleteAddDeclarationApplyResult.Completed>(
            journal.completeApply(
                CompleteAddDeclarationApply.admit(admitted, closed(admitted.plan)).refined(),
            ),
        )
        val tampered = "tampered".toByteArray()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                """UPDATE add_declaration_apply SET after_sha256 = ?,
                    after_content_base64 = ? WHERE plan_id = ?""",
            ).use { statement ->
                statement.setString(1, hash(tampered))
                statement.setString(2, Base64.getEncoder().encodeToString(tampered))
                statement.setString(3, admitted.plan.planId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }

        assertInstanceOf<LoadAddDeclarationPlanResult.Rejected>(
            open(database).load(admitted.plan.planId),
        )
    }

    @Test
    fun `lost admission acknowledgement reports unknown while durable v3 survives`() {
        val database = tempDir.resolve("lost-admission-ack.db")
        val journal = open(
            database,
            ThrowAfterCommit(SqliteJournalCommitOperation.APPLY_ADMISSION),
        )
        val prepared = prepared(journal)

        val result = assertInstanceOf<BeginAddDeclarationApplyResult.CommitOutcomeUnknown>(
            journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined()),
        )
        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            open(database).load(result.planId),
        ).record

        assertEquals(
            io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage.APPLY_ADMITTED,
            reopened.stage,
        )
    }

    @Test
    fun `lost completion acknowledgement reports unknown while durable v4 survives`() {
        val database = tempDir.resolve("lost-completion-ack.db")
        val journal = open(
            database,
            ThrowAfterCommit(SqliteJournalCommitOperation.APPLY_COMPLETION),
        )
        val prepared = prepared(journal)
        val admitted = assertInstanceOf<BeginAddDeclarationApplyResult.Begun>(
            journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined()),
        ).record

        val result = assertInstanceOf<CompleteAddDeclarationApplyResult.CommitOutcomeUnknown>(
            journal.completeApply(
                CompleteAddDeclarationApply.admit(admitted, closed(admitted.plan)).refined(),
            ),
        )
        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            open(database).load(result.planId),
        ).record

        assertEquals(
            io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage.APPLIED_UNVERIFIED,
            reopened.stage,
        )
    }

    @Test
    fun `post-commit connection observer failure preserves unknown admission outcome`() {
        val database = tempDir.resolve("close-admission-ack.db")
        val journal = open(
            database,
            ThrowOnCloseAfterCommit(SqliteJournalCommitOperation.APPLY_ADMISSION),
        )
        val prepared = prepared(journal)

        val result = assertInstanceOf<BeginAddDeclarationApplyResult.CommitOutcomeUnknown>(
            journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined()),
        )
        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            open(database).load(result.planId),
        ).record

        assertEquals(
            io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage.APPLY_ADMITTED,
            reopened.stage,
        )
    }

    @Test
    fun `ReviewRegression failed rollback after applied CAS preserves unknown outcome`() {
        val database = tempDir.resolve("unknown-admission-rollback.db")
        val journal = open(
            database,
            ThrowBeforeRollback(SqliteJournalCommitOperation.APPLY_ADMISSION),
        )
        val prepared = prepared(journal)
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE TRIGGER corrupt_apply_admission_reload
                        AFTER INSERT ON add_declaration_apply BEGIN
                        UPDATE add_declaration_plan SET plan_bytes = 'corrupt'
                        WHERE plan_id = NEW.plan_id; END""",
                )
            }
        }

        val result = journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined())

        assertInstanceOf<BeginAddDeclarationApplyResult.CommitOutcomeUnknown>(result)

        val completionDatabase = tempDir.resolve("unknown-completion-rollback.db")
        val completionJournal = open(
            completionDatabase,
            ThrowBeforeRollback(SqliteJournalCommitOperation.APPLY_COMPLETION),
        )
        val admitted = assertInstanceOf<BeginAddDeclarationApplyResult.Begun>(
            completionJournal.beginApply(BeginAddDeclarationApply.admit(prepared(completionJournal)).refined()),
        ).record
        DriverManager.getConnection("jdbc:sqlite:$completionDatabase").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE TRIGGER corrupt_apply_completion_reload AFTER UPDATE OF stage
                        ON add_declaration_apply WHEN NEW.stage = 'APPLIED_UNVERIFIED' BEGIN
                        UPDATE add_declaration_plan SET plan_bytes = 'corrupt'
                        WHERE plan_id = NEW.plan_id; END""",
                )
            }
        }
        val completion = completionJournal.completeApply(
            CompleteAddDeclarationApply.admit(admitted, closed(admitted.plan)).refined(),
        )
        assertInstanceOf<CompleteAddDeclarationApplyResult.CommitOutcomeUnknown>(completion)
    }

    private fun prepared(journal: SqliteAddDeclarationPlanJournal): RecoveryPreparedAddDeclaration {
        val plan = plan()
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(journal.store(plan)).record
        val evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined()
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(
                ApproveAddDeclarationPlan.admit(plan.planId, awaiting.version, evidence).refined(),
            ),
        ).record
        val revalidated = RevalidatedAddDeclaration.admit(
            plan,
            AddDeclarationRevalidationObservation.observe(
                generation = EvidenceGeneration.parse(plan.generation.value).refined(),
                target = plan.target,
                currentFile = plan.expectedFile.preimage,
                provenance = AddDeclarationSourceProvenance.AUTHORED,
                writability = AddDeclarationTargetWritability.WRITABLE,
            ).refined(),
        ).refined()
        return assertInstanceOf<PrepareAddDeclarationRecoveryResult.Prepared>(
            journal.prepareRecovery(PrepareAddDeclarationRecovery.admit(approved, revalidated).refined()),
        ).record
    }

    private fun closed(plan: PlannedAddDeclaration): ClosedAddDeclarationApply =
        ClosedAddDeclarationApply.prove(
            plan,
            AddDeclarationApplyObservation.observe(
                plan = plan,
                changedDocumentPaths = setOf(TARGET),
                afterImage = plan.expectedFile.postimage,
                undoAvailability = AddDeclarationUndoAvailability.UNAVAILABLE,
            ).refined(),
        ).refined()

    private fun open(
        database: Path,
        observer: SqliteJournalConnectionObserver = SqliteJournalConnectionObserver.Disabled,
    ): SqliteAddDeclarationPlanJournal =
        assertInstanceOf<SqliteAddDeclarationPlanJournalOpenResult.Opened>(
            SqliteAddDeclarationPlanJournal.open(database, observer),
        ).journal

    private class ThrowAfterCommit(
        private val operation: SqliteJournalCommitOperation,
    ) : SqliteJournalConnectionObserver {
        override fun opened() = Unit

        override fun closed() = Unit

        override fun committed(operation: SqliteJournalCommitOperation) {
            if (operation == this.operation) error("simulated lost commit acknowledgement")
        }
    }

    private class ThrowOnCloseAfterCommit(
        private val operation: SqliteJournalCommitOperation,
    ) : SqliteJournalConnectionObserver {
        private var committed = false

        override fun opened() = Unit

        override fun closed() {
            if (committed) error("simulated post-commit connection observer failure")
        }

        override fun committed(operation: SqliteJournalCommitOperation) {
            if (operation == this.operation) committed = true
        }
    }

    private fun plan(): PlannedAddDeclaration {
        val before = "package sample\n".toByteArray()
        val after = "package sample\n\nfun added(): Int = 1\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(before),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()
        val owner = AddDeclarationSourceOwner.admit(
            sourceRoot = "$ROOT/indexer/src/main/kotlin",
            ideaModuleName = "kast.indexer.main",
            gradleBuildRoot = ROOT,
            gradleProjectPath = ":indexer",
            sourceSetName = "main",
        ).refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val generation = EvidenceGeneration.parse(7).refined()
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent = intent,
                generation = generation,
                target = target,
                expectedFile = ExpectedFileProof.admit(
                    target,
                    exact(before),
                    exact(after),
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

    private fun exact(bytes: ByteArray): ExactFileContentProof = ExactFileContentProof.admit(
        hash(bytes),
        Base64.getEncoder().encodeToString(bytes),
    ).refined()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
