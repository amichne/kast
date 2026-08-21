package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement

/** Evidence families that must be complete before a workspace can be published. */
enum class WorkspaceEvidenceKind {
    AdmittedSourceState,
    GradleOwnership,
    CompilerConfiguration,
    DependencyClasspath,
}

/** Closed failure proving which required evidence families were not reconciled. */
class WorkspaceEvidenceCoverageFailure internal constructor(
    val missing: Set<WorkspaceEvidenceKind>,
) {
    override fun equals(other: Any?): Boolean =
        other is WorkspaceEvidenceCoverageFailure && missing == other.missing

    override fun hashCode(): Int = missing.hashCode()

    override fun toString(): String = "WorkspaceEvidenceCoverageFailure(missing=$missing)"
}

/** Proof that every KCS-007 workspace evidence family was reconciled together. */
class CompleteWorkspaceEvidenceCoverage private constructor(
    evidence: Set<WorkspaceEvidenceKind>,
) {
    val evidence: Set<WorkspaceEvidenceKind> = evidence.toSet()

    companion object {
        /**
         * Proof transition: `Set<WorkspaceEvidenceKind> ->
         * Refinement<CompleteWorkspaceEvidenceCoverage, WorkspaceEvidenceCoverageFailure>`.
         *
         * Establishes exact coverage of admitted source state, Gradle ownership, compiler
         * configuration, and dependency/classpath identity. The closed expected failure retains
         * every missing family. Raw evidence-family sets may enter only at the reconciliation
         * adapter boundary.
         */
        fun admit(
            observed: Set<WorkspaceEvidenceKind>,
        ): Refinement<CompleteWorkspaceEvidenceCoverage, WorkspaceEvidenceCoverageFailure> {
            val missing = WorkspaceEvidenceKind.entries.toSet() - observed
            return if (missing.isEmpty()) {
                Refinement.Refined(CompleteWorkspaceEvidenceCoverage(observed))
            } else {
                Refinement.Rejected(WorkspaceEvidenceCoverageFailure(missing))
            }
        }
    }
}

/** Detached candidate captured from one canonical root and one admitted source state. */
data class WorkspaceCandidate(
    val root: CanonicalWorkspaceRoot,
    val sourceState: WorkspaceStateIdentity,
)

/** Candidate whose required evidence families were reconciled as one complete unit. */
class ReconciledWorkspace private constructor(
    val candidate: WorkspaceCandidate,
    val coverage: CompleteWorkspaceEvidenceCoverage,
    sourceRoots: Iterable<SourceRoot>,
) {
    val sourceRoots: List<SourceRoot> = sourceRoots.toList()

    companion object {
        /**
         * Proof transition: `(WorkspaceCandidate, Set<WorkspaceEvidenceKind>,
         * Iterable<SourceRoot>) -> Refinement<ReconciledWorkspace,
         * WorkspaceEvidenceCoverageFailure>`.
         *
         * Establishes complete evidence coverage for the exact captured candidate. The closed
         * expected failure retains every missing family. Admitted source-root ownership and
         * provenance remain attached to the reconciled candidate. Raw evidence-family sets may
         * enter only from the workspace reconciliation adapter.
         */
        fun admit(
            candidate: WorkspaceCandidate,
            observed: Set<WorkspaceEvidenceKind>,
            sourceRoots: Iterable<SourceRoot> = emptyList(),
        ): Refinement<ReconciledWorkspace, WorkspaceEvidenceCoverageFailure> = when (
            val coverage = CompleteWorkspaceEvidenceCoverage.admit(observed)
        ) {
            is Refinement.Refined -> Refinement.Refined(
                ReconciledWorkspace(candidate, coverage.value, sourceRoots),
            )
            is Refinement.Rejected -> coverage
        }
    }
}

/**
 * The sole immutable semantic state admitted for workspace-scoped reads.
 *
 * The retained [readLease] binds the exact canonical root and evidence generation once. All
 * remaining fields derive from the same reconciled candidate, so mixed candidate/generation
 * assembly is unavailable outside [publish].
 */
class PublishedWorkspace private constructor(
    private val reconciled: ReconciledWorkspace,
    val readLease: SemanticReadLease,
) {
    val root: CanonicalWorkspaceRoot
        get() = readLease.workspaceRoot

    val sourceState: WorkspaceStateIdentity
        get() = reconciled.candidate.sourceState

    val generation: EvidenceGeneration
        get() = readLease.generation

    val coverage: CompleteWorkspaceEvidenceCoverage
        get() = reconciled.coverage

    val sourceRoots: List<SourceRoot>
        get() = reconciled.sourceRoots

    companion object {
        /**
         * Proof transition: `(ReconciledWorkspace, EvidenceGeneration) -> PublishedWorkspace`.
         *
         * Establishes one immutable publication whose canonical root, admitted source state,
         * complete evidence coverage, typed source roots, semantic generation, and
         * generation-bound read lease cannot vary independently. Raw root and generation
         * extraction is permitted only at physical workspace and evidence-persistence boundaries.
         */
        fun publish(
            reconciled: ReconciledWorkspace,
            generation: EvidenceGeneration,
        ): PublishedWorkspace = PublishedWorkspace(
            reconciled = reconciled,
            readLease = SemanticReadLease(reconciled.candidate.root, generation),
        )
    }
}

/** Exact runtime lifecycle; only [Ready] carries semantic read authority. */
sealed interface WorkspaceRuntimeState {
    data object Absent : WorkspaceRuntimeState

    data object Starting : WorkspaceRuntimeState

    data object Reconciling : WorkspaceRuntimeState

    data class Ready(
        val workspace: PublishedWorkspace,
    ) : WorkspaceRuntimeState

    data class Blocked(
        val blocker: WorkspacePublicationBlocker,
    ) : WorkspaceRuntimeState

    data object Stopping : WorkspaceRuntimeState
}

/** Public detached observation for `workspace.inspect`. */
fun interface WorkspaceInspectionOperations {
    fun inspect(): WorkspaceRuntimeState
}

/** Event sink that immediately withdraws a ready workspace. */
fun interface WorkspaceInvalidationSink {
    fun observe(signal: WorkspaceSignal)
}

/** Finite blockers produced by candidate capture, reconciliation, or publication. */
sealed interface WorkspacePublicationBlocker {
    data object CandidateCaptureUnavailable : WorkspacePublicationBlocker

    data object ReconciliationUnavailable : WorkspacePublicationBlocker

    data class IncompleteEvidence(
        val failure: WorkspaceEvidenceCoverageFailure,
    ) : WorkspacePublicationBlocker

    data object PublicationUnavailable : WorkspacePublicationBlocker
}

/** Closed result of capturing the current canonical workspace candidate. */
sealed interface WorkspaceCandidateCapture {
    data class Captured(
        val candidate: WorkspaceCandidate,
    ) : WorkspaceCandidateCapture

    data class Rejected(
        val blocker: WorkspacePublicationBlocker,
    ) : WorkspaceCandidateCapture
}

/** Closed result of reconciling all required evidence for one exact candidate. */
sealed interface WorkspaceCandidateReconciliation {
    data class Reconciled(
        val workspace: ReconciledWorkspace,
    ) : WorkspaceCandidateReconciliation

    data class Rejected(
        val blocker: WorkspacePublicationBlocker,
    ) : WorkspaceCandidateReconciliation
}

/** Physical candidate-capture and reconciliation boundary. */
interface WorkspaceReconciliationPort {
    /**
     * Proof transition: `Set<WorkspaceSignal> -> WorkspaceCandidateCapture`.
     *
     * Establishes a detached canonical root and admitted source-state identity, or a finite
     * blocker. Raw platform state may be observed only by the implementation adapter.
     */
    fun capture(signals: Set<WorkspaceSignal>): WorkspaceCandidateCapture

    /**
     * Proof transition: `WorkspaceCandidate -> WorkspaceCandidateReconciliation`.
     *
     * Establishes complete evidence for the exact candidate, or a finite blocker. Live Gradle,
     * compiler, and indexing state may be used only inside the implementation adapter.
     */
    fun reconcile(candidate: WorkspaceCandidate): WorkspaceCandidateReconciliation
}

/** Closed result of one requested publication cycle. */
sealed interface WorkspacePublicationRun {
    data object NoWork : WorkspacePublicationRun

    data class Published(
        val workspace: PublishedWorkspace,
    ) : WorkspacePublicationRun

    data class Unchanged(
        val workspace: PublishedWorkspace,
    ) : WorkspacePublicationRun

    data object Invalidated : WorkspacePublicationRun

    data class Blocked(
        val blocker: WorkspacePublicationBlocker,
    ) : WorkspacePublicationRun
}
