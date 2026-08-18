package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.workspace.contract.WorkspaceCandidateCapture
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateReconciliation
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class IntellijWorkspaceReconciliationPortTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `adapter physically canonicalizes root and retains complete Gradle evidence`() {
        val observedSignals = mutableListOf<Set<WorkspaceSignal>>()
        val adapter = IntellijWorkspaceReconciliationPort(
            workspaceRoot = { tempDir.resolve(".") },
            gradleModel = GradleWorkspaceModelPort { _, signals ->
                observedSignals += signals
                GradleWorkspaceModelCapture.Captured(WorkspaceStateIdentity("gradle-model"))
            },
            reconcile = IntellijWorkspaceReconciliation { _ ->
                IntellijWorkspaceReconciliationResult.Reconciled(
                    WorkspaceEvidenceKind.entries.toSet(),
                )
            },
        )

        val captured = adapter.capture(setOf(WorkspaceSignal.BuildSemantic))
            as WorkspaceCandidateCapture.Captured
        val reconciled = adapter.reconcile(captured.candidate)
            as WorkspaceCandidateReconciliation.Reconciled

        assertEquals(tempDir.toRealPath().toString(), captured.candidate.root.value)
        assertEquals("gradle-model", captured.candidate.sourceState.value)
        assertEquals(listOf(setOf(WorkspaceSignal.BuildSemantic)), observedSignals)
        assertEquals(
            WorkspaceEvidenceKind.entries.toSet(),
            reconciled.workspace.coverage.evidence,
        )
    }

    @Test
    fun `adapter rejects incomplete reconciliation coverage`() {
        val missing = WorkspaceEvidenceKind.CompilerConfiguration
        val adapter = IntellijWorkspaceReconciliationPort(
            workspaceRoot = { tempDir },
            gradleModel = GradleWorkspaceModelPort { _, _ ->
                GradleWorkspaceModelCapture.Captured(WorkspaceStateIdentity("gradle-model"))
            },
            reconcile = IntellijWorkspaceReconciliation { _ ->
                IntellijWorkspaceReconciliationResult.Reconciled(
                    WorkspaceEvidenceKind.entries.toSet() - missing,
                )
            },
        )
        val candidate = (adapter.capture(emptySet()) as WorkspaceCandidateCapture.Captured).candidate

        val rejected = adapter.reconcile(candidate) as WorkspaceCandidateReconciliation.Rejected
        val incomplete = rejected.blocker as WorkspacePublicationBlocker.IncompleteEvidence
        assertEquals(setOf(missing), incomplete.failure.missing)
    }
}
