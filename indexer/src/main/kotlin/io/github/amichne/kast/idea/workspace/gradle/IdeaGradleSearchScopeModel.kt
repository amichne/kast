package io.github.amichne.kast.idea.workspace.gradle

import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance

/**
 * Proof transition:
 * IdeaGradleProjectLoadBridge.GradleWorkspaceModel + CanonicalWorkspaceRoot
 * to WorkspaceSearchScopeModelCompilation.
 *
 * Converts IDEA's already-imported exact Gradle associations into detached ownership while
 * preserving model-declared source provenance. The closed failures belong to
 * [WorkspaceSearchScopeModelCompilation]. Java booleans and live project-model objects are
 * extracted only at this indexer boundary; no import, refresh, scan, or filesystem I/O occurs.
 */
internal fun IdeaGradleProjectLoadBridge.GradleWorkspaceModel.toWorkspaceSearchScopeModel(
    workspaceRoot: CanonicalWorkspaceRoot,
): WorkspaceSearchScopeModelCompilation = WorkspaceSearchScopeModel.compile(
    workspaceRoot = workspaceRoot,
    modelState = if (importedModelComplete()) {
        ImportedWorkspaceModelState.COMPLETE
    } else {
        ImportedWorkspaceModelState.INCOMPLETE
    },
    boundaries = moduleAssociations().flatMap { module ->
        module.sourceSets().flatMap { sourceSet ->
            sourceSet.sourceRoots().map { sourceRoot ->
                WorkspaceSourceRootBoundary(
                    ideaModuleName = module.ideaModuleName(),
                    linkedBuildRoot = module.linkedBuildRoot(),
                    gradleProjectPath = module.gradleProjectPath(),
                    sourceSetName = sourceSet.sourceSetName(),
                    sourceRoot = sourceRoot.path(),
                    provenance = sourceRoot.provenance().toWorkspaceProvenance(),
                )
            }
        }
    },
)

/**
 * Proof transition:
 * IdeaGradleProjectLoadBridge.GradleSourceRootProvenance to WorkspaceSourceRootProvenance.
 *
 * Preserves the Gradle model's authored/generated classification and maps unresolved evidence to
 * the contract's closed UNKNOWN state. Raw Java variants are extracted only in this adapter.
 */
private fun IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.toWorkspaceProvenance():
    WorkspaceSourceRootProvenance = when (this) {
    is IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Authored ->
        WorkspaceSourceRootProvenance.AUTHORED
    is IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Generated ->
        WorkspaceSourceRootProvenance.GENERATED
    is IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Unknown ->
        WorkspaceSourceRootProvenance.UNKNOWN
}
