package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.evidence.contract.OpenCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationDiscard
import io.github.amichne.kast.evidence.contract.WorkspacePublicationFailure
import io.github.amichne.kast.evidence.contract.WorkspacePublicationOpening
import io.github.amichne.kast.evidence.contract.WorkspacePublicationPreparation
import io.github.amichne.kast.evidence.contract.WorkspacePublicationResult
import io.github.amichne.kast.evidence.contract.WorkspacePublicationTransaction
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.IndexSynchronizationResult
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateCapture
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateReconciliation
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceObservation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceObservationOperations
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkspacePublicationTest {
    @Test
    fun `cancellation during reconciliation discards open publication once`() =
        assertCancellationCleanup(PublicationCancellationStage.RECONCILIATION)

    @Test
    fun `cancellation during second capture discards open publication once`() =
        assertCancellationCleanup(PublicationCancellationStage.VERIFICATION)

    @Test
    fun `cancellation during preparation discards open publication once`() =
        assertCancellationCleanup(PublicationCancellationStage.PREPARATION)

    @Test
    fun `cancellation during commit discards prepared publication once`() =
        assertCancellationCleanup(PublicationCancellationStage.COMMIT)

    @Test
    fun `cancellation cleanup rejection blocks publication and preserves original cancellation`() {
        val failure = java.util.concurrent.CancellationException("cancelled reconciliation")
        val candidate = candidate("/workspace", "next")
        val inspection =
            ScriptedWorkspaceReconciliationPort(candidate).apply {
                beforeReconcile = { throw failure }
            }
        val publication =
            RecordingWorkspacePublicationTransaction().apply {
                onDiscard = { WorkspacePublicationDiscard.Rejected(WorkspacePublicationFailure.StorageUnavailable) }
            }
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)

        assertSame(
            failure,
            assertThrows(java.util.concurrent.CancellationException::class.java) { coordinator.reconcile() },
        )
        assertEquals(1, publication.openDiscarded)
        assertEquals(0, publication.preparedDiscarded)
        assertEquals(
            WorkspaceRuntimeState.Blocked(WorkspacePublicationBlocker.PublicationUnavailable),
            coordinator.inspect(),
        )
    }

    @Test
    fun `cancellation cleanup exception is suppressed without retrying discard`() {
        val failure = java.util.concurrent.CancellationException("cancelled reconciliation")
        val cleanupFailure = java.io.IOException("cleanup failed")
        val candidate = candidate("/workspace", "next")
        val inspection =
            ScriptedWorkspaceReconciliationPort(candidate).apply {
                beforeReconcile = { throw failure }
            }
        val publication =
            RecordingWorkspacePublicationTransaction().apply {
                onDiscard = { throw cleanupFailure }
            }
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)

        assertSame(
            failure,
            assertThrows(java.util.concurrent.CancellationException::class.java) { coordinator.reconcile() },
        )
        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertEquals(1, publication.openDiscarded)
        assertEquals(0, publication.preparedDiscarded)
        assertEquals(
            WorkspaceRuntimeState.Blocked(WorkspacePublicationBlocker.PublicationUnavailable),
            coordinator.inspect(),
        )
    }

    private fun assertCancellationCleanup(stage: PublicationCancellationStage) {
        val first = candidate("/workspace", "first")
        val next = candidate("/workspace", "next")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first, next, next, next, next)
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)
        val initial = assertInstanceOf(WorkspacePublicationRun.Published::class.java, coordinator.reconcile()).workspace
        val failure = java.util.concurrent.CancellationException("cancelled publication")
        when (stage) {
            PublicationCancellationStage.RECONCILIATION -> inspection.beforeReconcile = { throw failure }
            PublicationCancellationStage.VERIFICATION -> {
                var captures = 0
                inspection.beforeCapture = { if (++captures == 2) throw failure }
            }
            PublicationCancellationStage.PREPARATION -> publication.beforePrepare = { throw failure }
            PublicationCancellationStage.COMMIT -> publication.beforeCommit = { throw failure }
        }
        val refreshed = mutableListOf<PublishedWorkspace>()
        val readiness = recoveryReadiness(coordinator, next, refreshed)

        assertSame(failure, assertThrows(java.util.concurrent.CancellationException::class.java) { readiness.ready() })
        val expectedPreparedDiscards = if (stage == PublicationCancellationStage.COMMIT) 1 else 0
        assertEquals(expectedPreparedDiscards, publication.preparedDiscarded)
        assertEquals(1 - expectedPreparedDiscards, publication.openDiscarded)
        assertEquals(1, publication.discarded)
        assertEquals(listOf(initial), publication.committed)
        assertEquals(SemanticReadLeaseUse.Moved, coordinator.whileCurrent(initial.readLease) { error("stale lease") })
        inspection.beforeReconcile = {}
        inspection.beforeCapture = {}
        publication.beforePrepare = {}
        publication.beforeCommit = {}

        val recovered =
            assertInstanceOf(IndexSynchronizationResult.Synchronized::class.java, readiness.ready()).workspace
        assertEquals(2L, recovered.generation.value)
        assertEquals(listOf(initial, initial), refreshed)
        assertEquals(1, publication.discarded)
        assertEquals(SemanticReadLeaseUse.Moved, coordinator.whileCurrent(initial.readLease) { error("stale lease") })
    }

    @Test
    fun `readiness recovers candidate movement on the next request without reviving the prior lease`() {
        val first = candidate("/workspace", "first")
        val next = candidate("/workspace", "next")
        val moved = candidate("/workspace", "moved")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first, next, moved, moved, moved)
        val coordinator = WorkspacePublicationCoordinator(inspection, RecordingWorkspacePublicationTransaction())
        val initial = assertInstanceOf(WorkspacePublicationRun.Published::class.java, coordinator.reconcile()).workspace
        val refreshed = mutableListOf<PublishedWorkspace>()
        val readiness = recoveryReadiness(coordinator, moved, refreshed)

        assertInstanceOf(IndexSynchronizationResult.Rejected::class.java, readiness.ready())
        assertEquals(WorkspaceRuntimeState.Reconciling, coordinator.inspect())
        assertEquals(SemanticReadLeaseUse.Moved, coordinator.whileCurrent(initial.readLease) { error("stale lease") })

        val recovered =
            assertInstanceOf(IndexSynchronizationResult.Synchronized::class.java, readiness.ready()).workspace
        assertEquals(moved.sourceState, recovered.sourceState)
        assertEquals(2L, recovered.generation.value)
        assertEquals(listOf(initial, initial), refreshed)
        assertEquals(SemanticReadLeaseUse.Moved, coordinator.whileCurrent(initial.readLease) { error("stale lease") })
    }

    @Test
    fun `readiness recovers publication failure on the next request`() {
        val first = candidate("/workspace", "first")
        val next = candidate("/workspace", "next")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first, next, next, next, next)
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)
        val initial = assertInstanceOf(WorkspacePublicationRun.Published::class.java, coordinator.reconcile()).workspace
        val refreshed = mutableListOf<PublishedWorkspace>()
        val readiness = recoveryReadiness(coordinator, next, refreshed)
        publication.rejectNext = true

        assertInstanceOf(IndexSynchronizationResult.Rejected::class.java, readiness.ready())
        assertInstanceOf(WorkspaceRuntimeState.Blocked::class.java, coordinator.inspect())
        assertEquals(listOf(initial), refreshed)

        val recovered =
            assertInstanceOf(IndexSynchronizationResult.Synchronized::class.java, readiness.ready()).workspace
        assertEquals(2L, recovered.generation.value)
        assertEquals(listOf(initial, initial), refreshed)
        assertEquals(listOf(initial, recovered), publication.committed)
    }

    @Test
    fun `readiness recovers an interrupted publication on the next request`() {
        val first = candidate("/workspace", "first")
        val next = candidate("/workspace", "next")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first, next, next)
        val coordinator = WorkspacePublicationCoordinator(inspection, RecordingWorkspacePublicationTransaction())
        val initial = assertInstanceOf(WorkspacePublicationRun.Published::class.java, coordinator.reconcile()).workspace
        val refreshed = mutableListOf<PublishedWorkspace>()
        val readiness = recoveryReadiness(coordinator, next, refreshed)
        inspection.beforeCapture = { throw java.util.concurrent.CancellationException("interrupted") }

        assertThrows(java.util.concurrent.CancellationException::class.java) { readiness.ready() }
        assertEquals(WorkspaceRuntimeState.Reconciling, coordinator.inspect())
        inspection.beforeCapture = {}

        val recovered =
            assertInstanceOf(IndexSynchronizationResult.Synchronized::class.java, readiness.ready()).workspace
        assertEquals(2L, recovered.generation.value)
        assertEquals(listOf(initial, initial), refreshed)
        assertEquals(SemanticReadLeaseUse.Moved, coordinator.whileCurrent(initial.readLease) { error("stale lease") })
    }

    private fun recoveryReadiness(
        coordinator: WorkspacePublicationCoordinator,
        source: WorkspaceCandidate,
        refreshed: MutableList<PublishedWorkspace>,
    ): WorkspaceIndexSynchronizationService =
        WorkspaceIndexSynchronizationService(
            coordinator,
            WorkspaceIndexRefreshOperations { prior ->
                refreshed += prior
                WorkspaceIndexRefresh.Refreshed
            },
            WorkspaceIndexPublicationOperations(coordinator::reconcileAfterIndexRefresh),
            WorkspaceSourceObservationOperations { WorkspaceSourceObservation.Observed(source.sourceState) },
            coordinator.transitions,
            refreshBasis = coordinator,
        )

    @Test
    fun `lease guard executes only while the exact publication remains current`() {
        val first = candidate("/workspace", "first")
        val coordinator =
            WorkspacePublicationCoordinator(
                ScriptedWorkspaceReconciliationPort(first, first),
                RecordingWorkspacePublicationTransaction(),
            )
        val published =
            assertInstanceOf(
                    WorkspacePublicationRun.Published::class.java,
                    coordinator.reconcile(),
                )
                .workspace
        val calls = AtomicInteger()

        assertEquals(
            SemanticReadLeaseUse.Completed(published.readLease),
            coordinator.whileCurrent(published.readLease) {
                calls.incrementAndGet()
                published.readLease
            },
        )
        coordinator.observe(WorkspaceSignal.Source)
        assertEquals(
            SemanticReadLeaseUse.Moved,
            coordinator.whileCurrent(published.readLease) {
                calls.incrementAndGet()
                published.readLease
            },
        )
        assertEquals(1, calls.get())
    }

    @Test
    fun `resulting publication starts only from the exact current lease and advances generation`() {
        val first = candidate("/workspace", "first")
        val next = candidate("/workspace", "next")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val coordinator =
            WorkspacePublicationCoordinator(
                inspection,
                RecordingWorkspacePublicationTransaction(),
            )
        val initial =
            assertInstanceOf(
                    WorkspacePublicationRun.Published::class.java,
                    coordinator.reconcile(),
                )
                .workspace
        inspection.enqueue(next, next)

        val published =
            assertInstanceOf(
                    ResultingWorkspacePublicationResult.Published::class.java,
                    coordinator.reconcileAfter(initial.readLease),
                )
                .publication

        assertEquals(initial.readLease, published.prior)
        assertEquals(2L, published.workspace.generation.value)
        assertEquals(initial.root, published.workspace.root)
    }

    @Test
    fun `resulting publication rejects a lease other than the current publication`() {
        val first = candidate("/workspace", "first")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val coordinator =
            WorkspacePublicationCoordinator(
                inspection,
                RecordingWorkspacePublicationTransaction(),
            )
        val initial =
            assertInstanceOf(
                    WorkspacePublicationRun.Published::class.java,
                    coordinator.reconcile(),
                )
                .workspace
        val wrong = SemanticReadLease(initial.root, evidenceGeneration(0L))

        val rejected =
            assertInstanceOf(
                ResultingWorkspacePublicationResult.Rejected::class.java,
                coordinator.reconcileAfter(wrong),
            )

        assertInstanceOf(
            ResultingWorkspacePublicationFailure.PriorPublicationMismatch::class.java,
            rejected.failure,
        )
        assertEquals(WorkspaceRuntimeState.Ready(initial), coordinator.inspect())
    }

    @Test
    fun `resulting publication rejects an unchanged semantic generation`() {
        val first = candidate("/workspace", "stable")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)
        val initial =
            assertInstanceOf(
                    WorkspacePublicationRun.Published::class.java,
                    coordinator.reconcile(),
                )
                .workspace
        inspection.enqueue(first, first)
        publication.unchangedNext = true

        val rejected =
            assertInstanceOf(
                ResultingWorkspacePublicationResult.Rejected::class.java,
                coordinator.reconcileAfter(initial.readLease),
            )

        assertEquals(
            ResultingWorkspacePublicationFailure.InvalidResult(
                ResultingWorkspacePublicationAdmissionFailure.GENERATION_NOT_NEWER
            ),
            rejected.failure,
        )
        val ready =
            assertInstanceOf(
                WorkspaceRuntimeState.Ready::class.java,
                coordinator.inspect(),
            )
        assertEquals(initial.readLease, ready.workspace.readLease)
    }

    @Test
    fun `workspace movement discards the in-flight candidate`() {
        val first = candidate("/workspace", "first")
        val moving = candidate("/workspace", "moving")
        val moved = candidate("/workspace", "moved")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)

        assertInstanceOf(WorkspacePublicationRun.Published::class.java, coordinator.reconcile())
        coordinator.observe(WorkspaceSignal.Source)
        inspection.enqueue(moving, moved)

        assertEquals(WorkspacePublicationRun.Invalidated, coordinator.reconcile())
        assertEquals(1, publication.committed.size)
        assertEquals(1, publication.discarded)
        assertEquals(WorkspaceRuntimeState.Reconciling, coordinator.inspect())
    }

    @Test
    fun `publication exposes one immutable generation without mixed fields`() {
        val first = candidate("/workspace", "first")
        val next = candidate("/moved-workspace", "next")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)
        coordinator.reconcile()
        coordinator.observe(WorkspaceSignal.GitWorktree)
        inspection.enqueue(next, next)
        val sourceRoot = sourceRoot(next.root)
        inspection.sourceRoots = listOf(sourceRoot)
        publication.beforeCommit = {
            assertEquals(WorkspaceRuntimeState.Reconciling, coordinator.inspect())
        }

        val run =
            assertInstanceOf(
                WorkspacePublicationRun.Published::class.java,
                coordinator.reconcile(),
            )
        val ready = assertInstanceOf(WorkspaceRuntimeState.Ready::class.java, coordinator.inspect())

        assertEquals(run.workspace, ready.workspace)
        assertEquals("/moved-workspace", ready.workspace.root.value)
        assertEquals("next", ready.workspace.sourceState.value)
        assertEquals(2, ready.workspace.generation.value)
        assertEquals(ready.workspace.root, ready.workspace.readLease.workspaceRoot)
        assertEquals(ready.workspace.generation, ready.workspace.readLease.generation)
        assertEquals(WorkspaceEvidenceKind.entries.toSet(), ready.workspace.coverage.evidence)
        assertEquals(listOf(sourceRoot), ready.workspace.sourceRoots)
    }

    @Test
    fun `failed publication preserves the prior committed generation`() {
        val first = candidate("/workspace", "first")
        val next = candidate("/workspace", "next")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)
        coordinator.reconcile()
        val prior = publication.committed.single()
        coordinator.observe(WorkspaceSignal.Source)
        inspection.enqueue(next, next)
        publication.rejectNext = true

        val blocked =
            assertInstanceOf(
                WorkspacePublicationRun.Blocked::class.java,
                coordinator.reconcile(),
            )

        assertEquals(WorkspacePublicationBlocker.PublicationUnavailable, blocked.blocker)
        assertEquals(listOf(prior), publication.committed)
        assertEquals(
            WorkspaceRuntimeState.Blocked(WorkspacePublicationBlocker.PublicationUnavailable),
            coordinator.inspect(),
        )
    }

    @Test
    fun `incomplete evidence cannot become Ready`() {
        val candidate = candidate("/workspace", "candidate")
        val missing = WorkspaceEvidenceKind.DependencyClasspath
        val inspection =
            ScriptedWorkspaceReconciliationPort(candidate, candidate).apply {
                evidence = WorkspaceEvidenceKind.entries.toSet() - missing
            }
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)

        val blocked =
            assertInstanceOf(
                WorkspacePublicationRun.Blocked::class.java,
                coordinator.reconcile(),
            )

        val incomplete = blocked.blocker as WorkspacePublicationBlocker.IncompleteEvidence
        assertEquals(setOf(missing), incomplete.failure.missing)
        assertEquals(emptyList<PublishedWorkspace>(), publication.committed)
    }
}

private class ScriptedWorkspaceReconciliationPort(vararg candidates: WorkspaceCandidate) : WorkspaceReconciliationPort {
    private val captures = ArrayDeque(candidates.toList())
    var evidence: Set<WorkspaceEvidenceKind> = WorkspaceEvidenceKind.entries.toSet()
    var sourceRoots: List<SourceRoot> = emptyList()
    var beforeCapture: () -> Unit = {}
    var beforeReconcile: () -> Unit = {}

    fun enqueue(vararg candidates: WorkspaceCandidate) {
        captures.addAll(candidates)
    }

    override fun capture(signals: Set<WorkspaceSignal>): WorkspaceCandidateCapture {
        beforeCapture()
        return WorkspaceCandidateCapture.Captured(captures.removeFirst())
    }

    override fun reconcile(candidate: WorkspaceCandidate): WorkspaceCandidateReconciliation {
        beforeReconcile()
        return when (val admitted = ReconciledWorkspace.admit(candidate, evidence, sourceRoots)) {
            is Refinement.Refined -> WorkspaceCandidateReconciliation.Reconciled(admitted.value)
            is Refinement.Rejected ->
                WorkspaceCandidateReconciliation.Rejected(
                    WorkspacePublicationBlocker.IncompleteEvidence(admitted.failure)
                )
        }
    }
}

private class RecordingWorkspacePublicationTransaction : WorkspacePublicationTransaction {
    val committed = mutableListOf<PublishedWorkspace>()
    var discarded = 0
    var openDiscarded = 0
    var preparedDiscarded = 0
    var rejectNext = false
    var unchangedNext = false
    var beforeCommit: () -> Unit = {}
    var beforePrepare: () -> Unit = {}
    var onDiscard: () -> WorkspacePublicationDiscard = { WorkspacePublicationDiscard.Discarded }

    override fun begin(): WorkspacePublicationOpening = WorkspacePublicationOpening.Opened(TestOpenPublication)

    override fun prepare(
        open: OpenCanonicalWorkspacePublication,
        candidate: ReconciledWorkspace,
    ): WorkspacePublicationPreparation {
        beforePrepare()
        return WorkspacePublicationPreparation.Prepared(TestPreparedPublication(candidate))
    }

    override fun commit(prepared: PreparedCanonicalWorkspacePublication): WorkspacePublicationResult {
        beforeCommit()
        if (rejectNext) {
            rejectNext = false
            return WorkspacePublicationResult.Rejected(WorkspacePublicationFailure.StorageUnavailable)
        }
        val candidate = (prepared as TestPreparedPublication).candidate
        val generation =
            if (unchangedNext) {
                committed.last().generation
            } else {
                evidenceGeneration(committed.size.toLong() + 1)
            }
        val workspace =
            PublishedWorkspace.publish(
                candidate,
                generation,
            )
        if (unchangedNext) {
            unchangedNext = false
            return WorkspacePublicationResult.Unchanged(workspace)
        }
        committed += workspace
        return WorkspacePublicationResult.Advanced(workspace)
    }

    override fun discard(open: OpenCanonicalWorkspacePublication): WorkspacePublicationDiscard {
        discarded += 1
        openDiscarded += 1
        return onDiscard()
    }

    override fun discard(prepared: PreparedCanonicalWorkspacePublication): WorkspacePublicationDiscard {
        discarded += 1
        preparedDiscarded += 1
        return onDiscard()
    }
}

private enum class PublicationCancellationStage {
    RECONCILIATION,
    VERIFICATION,
    PREPARATION,
    COMMIT,
}

private data object TestOpenPublication : OpenCanonicalWorkspacePublication

private data class TestPreparedPublication(val candidate: ReconciledWorkspace) : PreparedCanonicalWorkspacePublication

private fun candidate(
    root: String,
    identity: String,
): WorkspaceCandidate =
    WorkspaceCandidate(
        root = canonicalRoot(root),
        sourceState = WorkspaceStateIdentity(identity),
    )

private fun canonicalRoot(value: String): CanonicalWorkspaceRoot =
    when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(value))) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error(admitted.failure)
    }

private fun evidenceGeneration(value: Long): EvidenceGeneration =
    when (val admitted = EvidenceGeneration.parse(value)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error(admitted.failure)
    }

private fun sourceRoot(workspaceRoot: CanonicalWorkspaceRoot): SourceRoot =
    when (
        val admitted =
            SourceRoot.admit(
                GradleSourceRootEvidence(
                    ideaModuleName = "app.main",
                    workspaceRelativeBuildRoot = ".",
                    gradleProjectPath = ":app",
                    sourceSetName = "main",
                    workspaceRelativeSourceRoot = "app/src/main/kotlin",
                    provenance = SourceRootProvenance.Authored,
                )
            )
    ) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error(admitted.failure)
    }
