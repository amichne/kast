package io.github.amichne.kast.idea.workspace.gradle

import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance

/**
 * Proof transition:
 * IdeaGradleProjectLoadBridge.GradleWorkspaceModel + CanonicalWorkspaceRoot
 * to WorkspaceSearchScopeModelCompilation.
 *
 * Converts IDEA's already-imported exact Gradle associations into detached ownership while
 * preserving model-declared production/test kind and authored/generated provenance. The closed
 * failures belong to [WorkspaceSearchScopeModelCompilation]. Java booleans and live project-model
 * objects are extracted only at this indexer boundary; no import, refresh, scan, or filesystem I/O
 * occurs.
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
                    sourceKind = sourceRoot.provenance().toWorkspaceSourceKind(),
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

/**
 * Proof transition:
 * IdeaGradleProjectLoadBridge.GradleSourceRootProvenance to WorkspaceSourceRootKind.
 *
 * Establishes production or test source kind only when every exact Gradle model-evidence variant
 * agrees. Empty, resource, excluded, or mixed production/test evidence maps to the closed UNKNOWN
 * state and is rejected by [WorkspaceSearchScopeModel]. Raw Java model evidence is extracted only
 * in this adapter.
 */
private fun IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.toWorkspaceSourceKind():
    WorkspaceSourceRootKind {
    val evidence = modelEvidence()
    return when {
        evidence.isNotEmpty() && evidence.all(PRODUCTION_SOURCE_EVIDENCE::contains) ->
            WorkspaceSourceRootKind.PRODUCTION
        evidence.isNotEmpty() && evidence.all(TEST_SOURCE_EVIDENCE::contains) ->
            WorkspaceSourceRootKind.TEST
        else -> WorkspaceSourceRootKind.UNKNOWN
    }
}

private val PRODUCTION_SOURCE_EVIDENCE = setOf(
    IdeaGradleProjectLoadBridge.GradleSourceRootModelEvidence.SOURCE,
    IdeaGradleProjectLoadBridge.GradleSourceRootModelEvidence.SOURCE_GENERATED,
)

private val TEST_SOURCE_EVIDENCE = setOf(
    IdeaGradleProjectLoadBridge.GradleSourceRootModelEvidence.TEST,
    IdeaGradleProjectLoadBridge.GradleSourceRootModelEvidence.TEST_GENERATED,
)
