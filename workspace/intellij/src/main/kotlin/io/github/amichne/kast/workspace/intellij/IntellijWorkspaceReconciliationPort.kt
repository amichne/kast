package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateCapture
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateReconciliation
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Detached result of capturing the imported Gradle model identity. */
sealed interface GradleWorkspaceModelCapture {
    data class Captured(
        val sourceState: WorkspaceStateIdentity,
    ) : GradleWorkspaceModelCapture

    data object Unavailable : GradleWorkspaceModelCapture
}

/** Narrow Gradle-model capture boundary owned by the IntelliJ adapter. */
fun interface GradleWorkspaceModelPort {
    /**
     * Proof transition: `(CanonicalWorkspaceRoot, Set<WorkspaceSignal>) ->
     * GradleWorkspaceModelCapture`.
     *
     * Establishes one detached admitted source-state identity from the imported model, or the
     * finite unavailable state. Live Gradle and IntelliJ model objects may be read only inside the
     * implementation and cannot escape this result.
     */
    fun capture(
        root: CanonicalWorkspaceRoot,
        signals: Set<WorkspaceSignal>,
    ): GradleWorkspaceModelCapture
}

/** Detached result of the IntelliJ reconciliation effect. */
sealed interface IntellijWorkspaceReconciliationResult {
    data class Reconciled(
        val evidence: Set<WorkspaceEvidenceKind>,
        val sourceRoots: List<SourceRoot> = emptyList(),
    ) : IntellijWorkspaceReconciliationResult

    data object Unavailable : IntellijWorkspaceReconciliationResult
}

/** Narrow IntelliJ reconciliation effect over one detached candidate. */
fun interface IntellijWorkspaceReconciliation {
    /**
     * Proof transition: `WorkspaceCandidate -> IntellijWorkspaceReconciliationResult`.
     *
     * Establishes the evidence families and typed source roots reconciled for the exact candidate,
     * or the finite unavailable state. Live project, compiler, and index objects remain inside
     * the adapter.
     */
    fun reconcile(candidate: WorkspaceCandidate): IntellijWorkspaceReconciliationResult
}

/**
 * Physical IntelliJ/Gradle adapter for canonical candidate capture and reconciliation.
 *
 * The root is physically canonicalized before contract admission. All live model work remains in
 * injected adapter ports; only detached workspace contract values leave each call.
 */
class IntellijWorkspaceReconciliationPort(
    private val workspaceRoot: () -> Path,
    private val gradleModel: GradleWorkspaceModelPort,
    private val reconcile: IntellijWorkspaceReconciliation,
) : WorkspaceReconciliationPort {
    override fun capture(signals: Set<WorkspaceSignal>): WorkspaceCandidateCapture {
        val root = when (val captured = captureCanonicalRoot()) {
            is CanonicalRootCapture.Captured -> captured.root
            CanonicalRootCapture.Unavailable -> {
                return WorkspaceCandidateCapture.Rejected(
                    WorkspacePublicationBlocker.CandidateCaptureUnavailable,
                )
            }
        }
        val model = try {
            gradleModel.capture(root, signals)
        } catch (failure: Exception) {
            rethrowCancellation(failure)
            GradleWorkspaceModelCapture.Unavailable
        }
        return when (model) {
            is GradleWorkspaceModelCapture.Captured -> WorkspaceCandidateCapture.Captured(
                WorkspaceCandidate(root, model.sourceState),
            )
            GradleWorkspaceModelCapture.Unavailable -> WorkspaceCandidateCapture.Rejected(
                WorkspacePublicationBlocker.CandidateCaptureUnavailable,
            )
        }
    }

    override fun reconcile(candidate: WorkspaceCandidate): WorkspaceCandidateReconciliation {
        val result = try {
            reconcile.reconcile(candidate)
        } catch (failure: Exception) {
            rethrowCancellation(failure)
            IntellijWorkspaceReconciliationResult.Unavailable
        }
        return when (result) {
            IntellijWorkspaceReconciliationResult.Unavailable ->
                WorkspaceCandidateReconciliation.Rejected(
                    WorkspacePublicationBlocker.ReconciliationUnavailable,
                )
            is IntellijWorkspaceReconciliationResult.Reconciled -> when (
                val admitted = ReconciledWorkspace.admit(
                    candidate,
                    result.evidence,
                    result.sourceRoots,
                )
            ) {
                is Refinement.Refined -> WorkspaceCandidateReconciliation.Reconciled(admitted.value)
                is Refinement.Rejected -> WorkspaceCandidateReconciliation.Rejected(
                    WorkspacePublicationBlocker.IncompleteEvidence(admitted.failure),
                )
            }
        }
    }

    /**
     * Proof transition: `Path -> CanonicalRootCapture`.
     *
     * Establishes physical canonicalization followed by lexical contract admission. I/O,
     * permission, and contract rejection remain the single finite unavailable state. Raw path
     * extraction is confined to this physical workspace boundary.
     */
    private fun captureCanonicalRoot(): CanonicalRootCapture {
        val physical = try {
            workspaceRoot().toRealPath()
        } catch (_: IOException) {
            return CanonicalRootCapture.Unavailable
        } catch (_: SecurityException) {
            return CanonicalRootCapture.Unavailable
        }
        return when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(physical)) {
            is Refinement.Refined -> CanonicalRootCapture.Captured(admitted.value)
            is Refinement.Rejected -> CanonicalRootCapture.Unavailable
        }
    }

    private fun rethrowCancellation(failure: Exception) {
        if (failure is CancellationException) throw failure
    }
}

private sealed interface CanonicalRootCapture {
    data class Captured(
        val root: CanonicalWorkspaceRoot,
    ) : CanonicalRootCapture

    data object Unavailable : CanonicalRootCapture
}
