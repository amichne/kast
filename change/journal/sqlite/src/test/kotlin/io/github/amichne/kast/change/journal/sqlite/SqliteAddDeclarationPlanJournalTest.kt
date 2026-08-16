package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RecoveryPreparedAddDeclaration
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class SqliteAddDeclarationPlanJournalTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `plan survives reopen and every operation releases its connection`() {
        val observer = CountingConnectionObserver()
        val database = tempDir.resolve("plans.db")
        val plan = plan()
        val journal = open(database, observer)

        val stored = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(journal.store(plan)).record
        val existing = assertInstanceOf<StoreAddDeclarationPlanResult.Existing>(
            journal.store(plan),
        ).record
        val reopened = open(database, observer)
        val loaded = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            reopened.load(plan.planId),
        ).record

        assertEquals(stored, loaded)
        assertEquals(stored, existing)
        assertEquals(0, observer.active.get())
        assertEquals(observer.opened.get(), observer.closed.get())
    }

    @Test
    fun `tampered canonical plan bytes fail closed on reload`() {
        val database = tempDir.resolve("tampered.db")
        val plan = plan()
        val journal = open(database)
        assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(journal.store(plan))
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                "UPDATE add_declaration_plan SET plan_bytes = ? WHERE plan_id = ?",
            ).use { statement ->
                statement.setString(1, "{\"tampered\":true}")
                statement.setString(2, plan.planId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val rejected = assertInstanceOf<LoadAddDeclarationPlanResult.Rejected>(
            open(database).load(plan.planId),
        )

        assertEquals(AddDeclarationPlanJournalFailure.CorruptRecord, rejected.failure)
    }

    @Test
    fun `two concurrent approvals have exactly one CAS winner`() {
        val observer = CountingConnectionObserver()
        val journal = open(tempDir.resolve("concurrent.db"), observer)
        val plan = plan()
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(journal.store(plan)).record
        val command = approval(awaiting)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = List(2) {
            executor.submit<ApproveAddDeclarationPlanResult> {
                ready.countDown()
                assertTrue(start.await(10, TimeUnit.SECONDS))
                journal.approve(command)
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        val results = futures.map { future -> future.get(20, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it is ApproveAddDeclarationPlanResult.Approved })
        val loser = assertInstanceOf<ApproveAddDeclarationPlanResult.Rejected>(
            results.single { it is ApproveAddDeclarationPlanResult.Rejected },
        )
        assertInstanceOf<AddDeclarationPlanJournalFailure.PriorStateMismatch>(loser.failure)
        assertEquals(0, observer.active.get())
        assertEquals(observer.opened.get(), observer.closed.get())
    }

    @Test
    fun `approval requires the exact awaiting version and retains prior state`() {
        val journal = open(tempDir.resolve("prior-state.db"))
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(
            journal.store(plan()),
        ).record
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(approval(awaiting)),
        ).record
        val replay = assertInstanceOf<ApproveAddDeclarationPlanResult.Rejected>(
            journal.approve(approval(awaiting)),
        )

        assertEquals(awaiting.stage, approved.priorStage)
        assertEquals(awaiting.version, approved.priorVersion)
        assertInstanceOf<AddDeclarationPlanJournalFailure.PriorStateMismatch>(replay.failure)
    }

    @Test
    fun `exact recovery survives reopen and every operation releases its connection`() {
        val observer = CountingConnectionObserver()
        val database = tempDir.resolve("recovery.db")
        val journal = open(database, observer)
        val plan = plan()
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(
            journal.store(plan),
        ).record
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(approval(awaiting)),
        ).record

        val prepared = assertInstanceOf<PrepareAddDeclarationRecoveryResult.Prepared>(
            journal.prepareRecovery(recovery(approved)),
        ).record
        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            open(database, observer).load(plan.planId),
        ).record

        assertEquals(prepared, reopened)
        assertEquals(plan.expectedFile.preimage, prepared.recovery.beforeImage)
        assertEquals(0, observer.active.get())
        assertEquals(observer.opened.get(), observer.closed.get())
    }

    @Test
    fun `two concurrent recovery preparations have exactly one CAS winner`() {
        val journal = open(tempDir.resolve("concurrent-recovery.db"))
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(
            journal.store(plan()),
        ).record
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(approval(awaiting)),
        ).record
        val command = recovery(approved)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = List(2) {
            executor.submit<PrepareAddDeclarationRecoveryResult> {
                ready.countDown()
                assertTrue(start.await(10, TimeUnit.SECONDS))
                journal.prepareRecovery(command)
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        val results = futures.map { future -> future.get(20, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it is PrepareAddDeclarationRecoveryResult.Prepared })
        val loser = assertInstanceOf<PrepareAddDeclarationRecoveryResult.Rejected>(
            results.single { it is PrepareAddDeclarationRecoveryResult.Rejected },
        )
        assertInstanceOf<AddDeclarationPlanJournalFailure.PriorStateMismatch>(loser.failure)
    }

    @Test
    fun `tampered recovery before image fails closed on reopen`() {
        val database = tempDir.resolve("tampered-recovery.db")
        val journal = open(database)
        val plan = plan()
        val awaiting = assertInstanceOf<StoreAddDeclarationPlanResult.Stored>(
            journal.store(plan),
        ).record
        val approved = assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(approval(awaiting)),
        ).record
        assertInstanceOf<PrepareAddDeclarationRecoveryResult.Prepared>(
            journal.prepareRecovery(recovery(approved)),
        )
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                "UPDATE add_declaration_recovery SET before_content_base64 = ? WHERE plan_id = ?",
            ).use { statement ->
                statement.setString(1, Base64.getEncoder().encodeToString("tampered".toByteArray()))
                statement.setString(2, plan.planId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val rejected = assertInstanceOf<LoadAddDeclarationPlanResult.Rejected>(
            open(database).load(plan.planId),
        )

        assertEquals(AddDeclarationPlanJournalFailure.CorruptRecord, rejected.failure)
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
    ): ApproveAddDeclarationPlan {
        val evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = awaiting.plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined()
        return ApproveAddDeclarationPlan.admit(
            planId = awaiting.plan.planId,
            expectedVersion = awaiting.version,
            evidence = evidence,
        ).refined()
    }

    private fun recovery(
        approved: PersistedAddDeclarationPlan.Approved,
    ): PrepareAddDeclarationRecovery =
        PrepareAddDeclarationRecovery.admit(
            revalidated = revalidated(approved.plan),
            expectedVersion = approved.version,
        ).refined()

    private fun revalidated(plan: PlannedAddDeclaration): RevalidatedAddDeclaration {
        val observation = AddDeclarationRevalidationObservation.observe(
            generation = EvidenceGeneration.parse(plan.generation.value).refined(),
            target = plan.target,
            currentFile = plan.expectedFile.preimage,
            provenance = AddDeclarationSourceProvenance.AUTHORED,
            writability = AddDeclarationTargetWritability.WRITABLE,
        ).refined()
        return RevalidatedAddDeclaration.admit(plan, observation).refined()
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
                    collisionSignature = "2".repeat(64),
                ).refined(),
                verification = AddDeclarationVerificationContract.forGeneration(generation),
                compilerEvidence = DetachedCompilerEvidence.admit("{\"complete\":true}").refined(),
            ).refined(),
        )
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private class CountingConnectionObserver : SqliteJournalConnectionObserver {
        val active = AtomicInteger()
        val opened = AtomicInteger()
        val closed = AtomicInteger()

        override fun opened() {
            opened.incrementAndGet()
            active.incrementAndGet()
        }

        override fun closed() {
            closed.incrementAndGet()
            active.decrementAndGet()
        }
    }

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
