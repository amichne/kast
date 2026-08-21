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
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateCapture
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateReconciliation
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspacePublicationTest {
    @Test
    fun `resulting publication starts only from the exact current lease and advances generation`() {
        val first = candidate("/workspace", "first")
        val next = candidate("/workspace", "next")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val coordinator = WorkspacePublicationCoordinator(
            inspection,
            RecordingWorkspacePublicationTransaction(),
        )
        val initial = assertInstanceOf(
            WorkspacePublicationRun.Published::class.java,
            coordinator.reconcile(),
        ).workspace
        inspection.enqueue(next, next)

        val published = assertInstanceOf(
            ResultingWorkspacePublicationResult.Published::class.java,
            coordinator.reconcileAfter(initial.readLease),
        ).publication

        assertEquals(initial.readLease, published.prior)
        assertEquals(2L, published.workspace.generation.value)
        assertEquals(initial.root, published.workspace.root)
    }

    @Test
    fun `resulting publication rejects a lease other than the current publication`() {
        val first = candidate("/workspace", "first")
        val inspection = ScriptedWorkspaceReconciliationPort(first, first)
        val coordinator = WorkspacePublicationCoordinator(
            inspection,
            RecordingWorkspacePublicationTransaction(),
        )
        val initial = assertInstanceOf(
            WorkspacePublicationRun.Published::class.java,
            coordinator.reconcile(),
        ).workspace
        val wrong = SemanticReadLease(initial.root, evidenceGeneration(0L))

        val rejected = assertInstanceOf(
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
        val initial = assertInstanceOf(
            WorkspacePublicationRun.Published::class.java,
            coordinator.reconcile(),
        ).workspace
        inspection.enqueue(first, first)
        publication.unchangedNext = true

        val rejected = assertInstanceOf(
            ResultingWorkspacePublicationResult.Rejected::class.java,
            coordinator.reconcileAfter(initial.readLease),
        )

        assertEquals(
            ResultingWorkspacePublicationFailure.InvalidResult(
                ResultingWorkspacePublicationAdmissionFailure.GENERATION_NOT_NEWER,
            ),
            rejected.failure,
        )
        val ready = assertInstanceOf(
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

        val run = assertInstanceOf(
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

        val blocked = assertInstanceOf(
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
        val inspection = ScriptedWorkspaceReconciliationPort(candidate, candidate).apply {
            evidence = WorkspaceEvidenceKind.entries.toSet() - missing
        }
        val publication = RecordingWorkspacePublicationTransaction()
        val coordinator = WorkspacePublicationCoordinator(inspection, publication)

        val blocked = assertInstanceOf(
            WorkspacePublicationRun.Blocked::class.java,
            coordinator.reconcile(),
        )

        val incomplete = blocked.blocker as WorkspacePublicationBlocker.IncompleteEvidence
        assertEquals(setOf(missing), incomplete.failure.missing)
        assertEquals(emptyList<PublishedWorkspace>(), publication.committed)
    }
}

private class ScriptedWorkspaceReconciliationPort(
    vararg candidates: WorkspaceCandidate,
) : WorkspaceReconciliationPort {
    private val captures = ArrayDeque(candidates.toList())
    var evidence: Set<WorkspaceEvidenceKind> = WorkspaceEvidenceKind.entries.toSet()
    var sourceRoots: List<SourceRoot> = emptyList()

    fun enqueue(vararg candidates: WorkspaceCandidate) {
        captures.addAll(candidates)
    }

    override fun capture(signals: Set<WorkspaceSignal>): WorkspaceCandidateCapture =
        WorkspaceCandidateCapture.Captured(captures.removeFirst())

    override fun reconcile(candidate: WorkspaceCandidate): WorkspaceCandidateReconciliation =
        when (val admitted = ReconciledWorkspace.admit(candidate, evidence, sourceRoots)) {
            is Refinement.Refined -> WorkspaceCandidateReconciliation.Reconciled(admitted.value)
            is Refinement.Rejected -> WorkspaceCandidateReconciliation.Rejected(
                WorkspacePublicationBlocker.IncompleteEvidence(admitted.failure),
            )
        }
}

private class RecordingWorkspacePublicationTransaction : WorkspacePublicationTransaction {
    val committed = mutableListOf<PublishedWorkspace>()
    var discarded = 0
    var rejectNext = false
    var unchangedNext = false
    var beforeCommit: () -> Unit = {}

    override fun begin(): WorkspacePublicationOpening =
        WorkspacePublicationOpening.Opened(TestOpenPublication)

    override fun prepare(
        open: OpenCanonicalWorkspacePublication,
        candidate: ReconciledWorkspace,
    ): WorkspacePublicationPreparation = WorkspacePublicationPreparation.Prepared(
        TestPreparedPublication(candidate),
    )

    override fun commit(
        prepared: PreparedCanonicalWorkspacePublication,
    ): WorkspacePublicationResult {
        beforeCommit()
        if (rejectNext) {
            rejectNext = false
            return WorkspacePublicationResult.Rejected(WorkspacePublicationFailure.StorageUnavailable)
        }
        val candidate = (prepared as TestPreparedPublication).candidate
        val generation = if (unchangedNext) {
            committed.last().generation
        } else {
            evidenceGeneration(committed.size.toLong() + 1)
        }
        val workspace = PublishedWorkspace.publish(
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
        return WorkspacePublicationDiscard.Discarded
    }

    override fun discard(
        prepared: PreparedCanonicalWorkspacePublication,
    ): WorkspacePublicationDiscard {
        discarded += 1
        return WorkspacePublicationDiscard.Discarded
    }
}

private data object TestOpenPublication : OpenCanonicalWorkspacePublication

private data class TestPreparedPublication(
    val candidate: ReconciledWorkspace,
) : PreparedCanonicalWorkspacePublication

private fun candidate(
    root: String,
    identity: String,
): WorkspaceCandidate = WorkspaceCandidate(
    root = canonicalRoot(root),
    sourceState = WorkspaceStateIdentity(identity),
)

private fun canonicalRoot(value: String): CanonicalWorkspaceRoot = when (
    val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(value))
) {
    is Refinement.Refined -> admitted.value
    is Refinement.Rejected -> error(admitted.failure)
}

private fun evidenceGeneration(value: Long): EvidenceGeneration = when (
    val admitted = EvidenceGeneration.parse(value)
) {
    is Refinement.Refined -> admitted.value
    is Refinement.Rejected -> error(admitted.failure)
}

private fun sourceRoot(workspaceRoot: CanonicalWorkspaceRoot): SourceRoot = when (
    val admitted = SourceRoot.admit(
        GradleSourceRootEvidence(
            ideaModuleName = "app.main",
            workspaceRelativeBuildRoot = ".",
            gradleProjectPath = ":app",
            sourceSetName = "main",
            workspaceRelativeSourceRoot = "app/src/main/kotlin",
            provenance = SourceRootProvenance.Authored,
        ),
    )
) {
    is Refinement.Refined -> admitted.value
    is Refinement.Rejected -> error(admitted.failure)
}
