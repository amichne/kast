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
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.VerifiedAddDeclaration
import io.github.amichne.kast.change.verify.spi.AddDeclarationCollisionObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationCompilerDiagnosticsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationExistingBindingsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationObservedIdentity
import io.github.amichne.kast.change.verify.spi.AddDeclarationOutboundBindingsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationExecutor
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationResult
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerification
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerificationResult
import io.github.amichne.kast.change.verify.spi.ObservedAddDeclarationVerification
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
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

class SqliteAddDeclarationVerificationJournalTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `verified receipt survives reopen without recovery capability`() {
        val database = tempDir.resolve("verified.db")
        val journal = open(database)
        val applied = applied(journal)
        val command = verification(applied)

        val completed = assertInstanceOf<CompleteAddDeclarationVerificationResult.Completed>(
            journal.completeVerification(command),
        ).record
        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            open(database).load(applied.plan.planId),
        ).record

        val restored = assertInstanceOf<VerifiedAddDeclaration>(reopened)
        assertEquals(AddDeclarationPlanStage.VERIFIED, completed.stage)
        assertEquals(completed.version, restored.version)
        assertEquals(completed.priorStage, restored.priorStage)
        assertEquals(completed.priorVersion, restored.priorVersion)
        assertEquals(completed.receipt.planId, restored.receipt.planId)
        assertEquals(completed.receipt.publication, restored.receipt.publication)
        assertEquals(command.verification.publication, completed.receipt.publication)
        assertEquals(command.verification.identity.targetPath, restored.receipt.identity.targetPath)
        assertEquals(
            command.verification.identity.sourceRange.startOffset,
            restored.receipt.identity.sourceRange.startOffset
        )
        assertEquals(
            command.verification.identity.sourceRange.endOffset,
            restored.receipt.identity.sourceRange.endOffset
        )
        assertEquals(command.verification.identity.packageName, restored.receipt.identity.packageName)
        assertEquals(command.verification.identity.declarationName, restored.receipt.identity.declarationName)
        assertEquals(command.verification.identity.declarationKind, restored.receipt.identity.declarationKind)
        assertEquals(applied.afterImage.sha256, completed.receipt.postimageSha256)
        assertEquals(applied.afterImage.sha256, restored.receipt.postimageSha256)
        assertEquals(1, recoveryRowCount(database))
    }

    @Test
    fun `tampered verification receipt fails closed on reopen`() {
        val database = tempDir.resolve("tampered-verified.db")
        val journal = open(database)
        val applied = applied(journal)
        assertInstanceOf<CompleteAddDeclarationVerificationResult.Completed>(
            journal.completeVerification(verification(applied)),
        )
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                "UPDATE add_declaration_verification SET verified_target_path = ? WHERE plan_id = ?",
            ).use { statement ->
                statement.setString(1, "$ROOT/indexer/src/main/kotlin/sample/Other.kt")
                statement.setString(2, applied.plan.planId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }

        assertInstanceOf<LoadAddDeclarationPlanResult.Rejected>(
            open(database).load(applied.plan.planId),
        )
    }

    @Test
    fun `lost verification acknowledgement retains durable v5`() {
        val database = tempDir.resolve("lost-verification-ack.db")
        val journal = open(
            database,
            ThrowAfterCommit(SqliteJournalCommitOperation.VERIFICATION_COMPLETION),
        )
        val applied = applied(journal)

        val result = assertInstanceOf<CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown>(
            journal.completeVerification(verification(applied)),
        )
        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            open(database).load(result.planId),
        ).record

        assertInstanceOf<VerifiedAddDeclaration>(reopened)
    }

    @Test
    fun `unproven rollback returns commit outcome unknown instead of rejection`() {
        val journal = open(
            tempDir.resolve("unknown-rollback.db"),
            ThrowBeforeRollback(SqliteJournalCommitOperation.VERIFICATION_COMPLETION),
        )
        val command = verification(applied(journal))
        assertInstanceOf<CompleteAddDeclarationVerificationResult.Completed>(
            journal.completeVerification(command),
        )

        val result = journal.completeVerification(command)

        assertInstanceOf<CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown>(result)
    }

    @Test
    fun `two verification CAS attempts have exactly one winner`() {
        val journal = open(tempDir.resolve("concurrent-verification.db"))
        val command = verification(applied(journal))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = List(2) {
            executor.submit<CompleteAddDeclarationVerificationResult> {
                ready.countDown()
                assertTrue(start.await(10, TimeUnit.SECONDS))
                journal.completeVerification(command)
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        val results = futures.map { it.get(20, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it is CompleteAddDeclarationVerificationResult.Completed })
        assertEquals(1, results.count { it is CompleteAddDeclarationVerificationResult.Rejected })
    }

    @Test
    private fun applied(journal: SqliteAddDeclarationPlanJournal): AppliedUnverifiedAddDeclaration {
        val plan = plan()
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(journal.store(plan)).record
        val approval = RawAddDeclarationPlanApprovalEvidence(
            planId = plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined()
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(
                ApproveAddDeclarationPlan.admit(plan.planId, awaiting.version, approval).refined(),
            ),
        ).record
        val revalidated = RevalidatedAddDeclaration.admit(
            plan,
            AddDeclarationRevalidationObservation.observe(
                generation = generation(plan.generation.value),
                target = plan.target,
                currentFile = plan.expectedFile.preimage,
                provenance = AddDeclarationSourceProvenance.AUTHORED,
                writability = AddDeclarationTargetWritability.WRITABLE,
            ).refined(),
        ).refined()
        val prepared = assertInstanceOf<PrepareAddDeclarationRecoveryResult.Prepared>(
            journal.prepareRecovery(
                PrepareAddDeclarationRecovery.admit(approved, revalidated).refined(),
            ),
        ).record
        val admitted = assertInstanceOf<BeginAddDeclarationApplyResult.Begun>(
            journal.beginApply(BeginAddDeclarationApply.admit(prepared).refined()),
        ).record
        val closure = ClosedAddDeclarationApply.prove(
            plan,
            AddDeclarationApplyObservation.observe(
                plan = plan,
                changedDocumentPaths = setOf(TARGET),
                afterImage = plan.expectedFile.postimage,
                undoAvailability = AddDeclarationUndoAvailability.UNAVAILABLE,
            ).refined(),
        ).refined()
        return assertInstanceOf<CompleteAddDeclarationApplyResult.Completed>(
            journal.completeApply(CompleteAddDeclarationApply.admit(admitted, closure).refined()),
        ).record
    }

    private fun verification(applied: AppliedUnverifiedAddDeclaration): CompleteAddDeclarationVerification =
        CompleteAddDeclarationVerification.admit(
            applied = applied,
            verification = observed(applied),
        ).refined()

    private fun observed(applied: AppliedUnverifiedAddDeclaration): ObservedAddDeclarationVerification {
        val publication = PublishedWorkspaceGeneration(
            generation(applied.plan.generation.value + 1),
            WorkspaceStateIdentity("workspace-result"),
        )
        val command = AddDeclarationVerificationCommand.admit(applied.plan, publication).refined()
        val identity = AddDeclarationObservedIdentity.admit(
            expected = applied.plan.expectedSemanticDelta,
            expectedTargetPath = applied.plan.target.targetPath,
            observedPackageName = "sample",
            observedDeclarationName = "added",
            observedKind = AddDeclarationKind.FUNCTION,
            observedStartOffset = 16,
            observedEndOffset = 36,
        ).refined()
        val context = ExpectedAddDeclarationCompilerContext.admit(
            generation = publication.generation,
            projectModelFingerprint = applied.plan.compilerContext.projectModelFingerprint,
            classpathFingerprint = applied.plan.compilerContext.classpathFingerprint,
            contextFiles = listOf(
                AddDeclarationCompilerContextFile.admit(
                    TARGET,
                    applied.afterImage.sha256.value,
                ).refined(),
            ),
            outboundReferenceCount = applied.plan.compilerContext.outboundReferenceCount,
        ).refined()
        return ObservationIssuer.issue(command, context, identity)
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
        val generation = generation(7)
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent = intent,
                generation = generation,
                target = target,
                expectedFile = ExpectedFileProof.admit(target, exact(before), exact(after)).refined(),
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

    private fun open(
        database: Path,
        observer: SqliteJournalConnectionObserver = SqliteJournalConnectionObserver.Disabled,
    ): SqliteAddDeclarationPlanJournal =
        assertInstanceOf<SqliteAddDeclarationPlanJournalOpenResult.Opened>(
            SqliteAddDeclarationPlanJournal.open(database, observer),
        ).journal

    private fun exact(bytes: ByteArray): ExactFileContentProof = ExactFileContentProof.admit(
        hash(bytes),
        Base64.getEncoder().encodeToString(bytes),
    ).refined()

    private fun generation(value: Long): EvidenceGeneration = EvidenceGeneration.parse(value).refined()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun recoveryRowCount(database: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM add_declaration_recovery").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun <T, F> Refinement<T, F>.refined(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private object ObservationIssuer : AddDeclarationVerificationExecutor() {
        override suspend fun verify(
            command: AddDeclarationVerificationCommand,
        ): AddDeclarationVerificationResult = error("direct invocation is not used")

        fun issue(
            command: AddDeclarationVerificationCommand,
            context: ExpectedAddDeclarationCompilerContext,
            identity: AddDeclarationObservedIdentity,
        ): ObservedAddDeclarationVerification = assertInstanceOf<AddDeclarationVerificationResult.Observed>(
            verified(
                command,
                context,
                identity,
                AddDeclarationCompilerDiagnosticsObservation.CLEAR,
                AddDeclarationCollisionObservation.ABSENT_COMPLETE,
                AddDeclarationOutboundBindingsObservation.PRESERVED_COMPLETE,
                AddDeclarationExistingBindingsObservation.PRESERVED_NO_CANDIDATES,
            ),
        ).verification
    }

    private class ThrowAfterCommit(
        private val operation: SqliteJournalCommitOperation,
    ) : SqliteJournalConnectionObserver {
        override fun opened() = Unit

        override fun closed() = Unit

        override fun committed(operation: SqliteJournalCommitOperation) {
            if (operation == this.operation) error("simulated lost commit acknowledgement")
        }
    }

    private class ThrowBeforeRollback(
        private val operation: SqliteJournalCommitOperation,
    ) : SqliteJournalConnectionObserver {
        override fun opened() = Unit

        override fun closed() = Unit

        override fun rollingBack(operation: SqliteJournalCommitOperation) {
            if (operation == this.operation) error("simulated rollback failure")
        }
    }

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
