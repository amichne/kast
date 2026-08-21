package io.github.amichne.kast.runtime.composition.platform

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.GradleWorkspaceModelCapture
import io.github.amichne.kast.workspace.intellij.GradleWorkspaceModelPort
import io.github.amichne.kast.workspace.intellij.IntellijWorkspaceReconciliation
import io.github.amichne.kast.workspace.intellij.IntellijWorkspaceReconciliationResult
import java.nio.file.Path

internal enum class InstalledGradleWorkspaceModelFailure {
    MODEL_ROOT_MISMATCH,
    MODEL_SOURCE_ROOT_MISMATCH,
}

/** One detached complete Gradle model shared by publication and semantic scope compilation. */
internal class InstalledGradleWorkspaceModel private constructor(
    val root: CanonicalWorkspaceRoot,
    val state: WorkspaceStateIdentity,
    val sourceRoots: List<SourceRoot>,
    val searchScope: WorkspaceSearchScopeModelCompilation.Compiled,
) {
    companion object {
        /**
         * Proof transition: `(CanonicalWorkspaceRoot, WorkspaceStateIdentity, List<SourceRoot>,
         * WorkspaceSearchScopeModelCompilation.Compiled) -> Refinement<
         * InstalledGradleWorkspaceModel, InstalledGradleWorkspaceModelFailure>`.
         *
         * Establishes one exact root and identical Gradle owner/source-root sets across workspace
         * publication and semantic search-scope authority. [InstalledGradleWorkspaceModelFailure]
         * closes root or source-root disagreement. Raw path comparison is confined to this model
         * adapter boundary.
         */
        fun admit(
            root: CanonicalWorkspaceRoot,
            state: WorkspaceStateIdentity,
            sourceRoots: List<SourceRoot>,
            searchScope: WorkspaceSearchScopeModelCompilation.Compiled,
        ): Refinement<InstalledGradleWorkspaceModel, InstalledGradleWorkspaceModelFailure> {
            if (searchScope.model.workspaceRoot != root) {
                return Refinement.Rejected(
                    InstalledGradleWorkspaceModelFailure.MODEL_ROOT_MISMATCH,
                )
            }
            val published = sourceRoots.mapTo(linkedSetOf()) { sourceRoot ->
                InstalledSourceRootIdentity(
                    sourceRoot.owner.module.value,
                    sourceRoot.owner.project.buildRoot.value,
                    sourceRoot.owner.project.projectPath.value,
                    sourceRoot.owner.sourceSet.value,
                    rootPath(root).resolve(sourceRoot.location.value).normalize().toString(),
                )
            }
            val semantic = searchScope.model.sourceRoots.mapTo(linkedSetOf()) { sourceRoot ->
                InstalledSourceRootIdentity(
                    sourceRoot.module.value,
                    sourceRoot.project.buildRoot.value,
                    sourceRoot.project.projectPath.value,
                    sourceRoot.sourceSet.value,
                    sourceRoot.sourceRoot.value,
                )
            }
            if (published != semantic) {
                return Refinement.Rejected(
                    InstalledGradleWorkspaceModelFailure.MODEL_SOURCE_ROOT_MISMATCH,
                )
            }
            return Refinement.Refined(
                InstalledGradleWorkspaceModel(root, state, sourceRoots.toList(), searchScope),
            )
        }
    }
}

internal sealed interface InstalledGradleModelRead {
    data class Captured(val model: InstalledGradleWorkspaceModel) : InstalledGradleModelRead
    data class Unavailable(
        val failure: InstalledGradleModelFailure,
    ) : InstalledGradleModelRead
}

internal fun interface InstalledGradleModelReadOperations {
    fun read(): InstalledGradleModelRead
}

/** Exact model adapter used by both workspace reconciliation and request-local compiler ports. */
internal class InstalledWorkspaceModelAdapter(
    private val models: InstalledGradleModelReadOperations,
) : GradleWorkspaceModelPort, IntellijWorkspaceReconciliation {
    private val lock = Any()
    private var state: State = State.Unavailable

    override fun capture(
        root: CanonicalWorkspaceRoot,
        signals: Set<WorkspaceSignal>,
    ): GradleWorkspaceModelCapture = when (val read = refresh()) {
        is InstalledGradleModelRead.Captured -> if (read.model.root == root) {
            GradleWorkspaceModelCapture.Captured(read.model.state)
        } else {
            GradleWorkspaceModelCapture.Unavailable
        }
        is InstalledGradleModelRead.Unavailable -> GradleWorkspaceModelCapture.Unavailable
    }

    override fun reconcile(candidate: WorkspaceCandidate): IntellijWorkspaceReconciliationResult =
        when (val read = refresh()) {
            is InstalledGradleModelRead.Captured -> if (
                read.model.root == candidate.root && read.model.state == candidate.sourceState
            ) {
                IntellijWorkspaceReconciliationResult.Reconciled(
                    WorkspaceEvidenceKind.entries.toSet(),
                    read.model.sourceRoots,
                )
            } else {
                IntellijWorkspaceReconciliationResult.Unavailable
            }
            is InstalledGradleModelRead.Unavailable ->
                IntellijWorkspaceReconciliationResult.Unavailable
        }

    fun searchScope(lease: SemanticReadLease): WorkspaceSearchScopeModelCompilation =
        when (val current = synchronized(lock) { state }) {
            is State.Ready -> if (current.model.root == lease.workspaceRoot) {
                current.model.searchScope
            } else {
                unavailableScope(lease.workspaceRoot)
            }
            State.Unavailable -> unavailableScope(lease.workspaceRoot)
        }

    private fun refresh(): InstalledGradleModelRead = models.read().also { read ->
        synchronized(lock) {
            state = when (read) {
                is InstalledGradleModelRead.Captured -> State.Ready(read.model)
                is InstalledGradleModelRead.Unavailable -> State.Unavailable
            }
        }
    }

    private sealed interface State {
        data object Unavailable : State

        data class Ready(
            val model: InstalledGradleWorkspaceModel,
        ) : State
    }
}

private data class InstalledSourceRootIdentity(
    val module: String,
    val buildRoot: String,
    val projectPath: String,
    val sourceSet: String,
    val path: String,
)

private fun rootPath(root: CanonicalWorkspaceRoot): Path = Path.of(root.value)

private fun unavailableScope(root: CanonicalWorkspaceRoot): WorkspaceSearchScopeModelCompilation =
    WorkspaceSearchScopeModel.compile(root, ImportedWorkspaceModelState.INCOMPLETE, emptyList())
