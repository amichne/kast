package io.github.amichne.kast.runtime.ide.read.composition

import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootProvenance
import java.nio.file.Path

/**
 * Proof transition: `DetachedIdeWorkspaceModel -> WorkspaceSearchScopeModelCompilation`.
 *
 * Preserves exact Gradle build/project ownership and source-root kind already admitted by the
 * detached existing-Project model, including IntelliJ's explicit generated-source flag. Raw paths
 * are reconstructed only from admitted canonical root plus admitted relative identities at this
 * native scope boundary. Compilation failures remain closed in
 * [WorkspaceSearchScopeModelCompilation].
 */
internal fun DetachedIdeWorkspaceModel.compileHostedSearchScope():
    WorkspaceSearchScopeModelCompilation {
    val root = Path.of(canonicalRoot.value)
    val boundaries = modules.flatMap { module ->
        module.sourceRoots.map { sourceRoot ->
            WorkspaceSourceRootBoundary(
                ideaModuleName = module.name.value,
                linkedBuildRoot = root.resolve(module.owner.buildRoot.value).normalize(),
                gradleProjectPath = module.owner.projectIdentity.value,
                sourceSetName = sourceRoot.kind.hostedSourceSet(),
                sourceRoot = root.resolve(sourceRoot.location.value).normalize(),
                sourceKind = sourceRoot.kind.hostedSourceKind(),
                provenance = sourceRoot.provenance.hostedProvenance(),
            )
        }
    }
    return WorkspaceSearchScopeModel.compile(
        canonicalRoot,
        ImportedWorkspaceModelState.COMPLETE,
        boundaries,
    )
}

private fun DetachedSourceRootKind.hostedSourceSet(): String = when (this) {
    DetachedSourceRootKind.PRODUCTION,
    DetachedSourceRootKind.RESOURCE,
        -> "main"
    DetachedSourceRootKind.TEST,
    DetachedSourceRootKind.TEST_RESOURCE,
        -> "test"
}

private fun DetachedSourceRootKind.hostedSourceKind(): WorkspaceSourceRootKind = when (this) {
    DetachedSourceRootKind.PRODUCTION,
    DetachedSourceRootKind.RESOURCE,
        -> WorkspaceSourceRootKind.PRODUCTION
    DetachedSourceRootKind.TEST,
    DetachedSourceRootKind.TEST_RESOURCE,
        -> WorkspaceSourceRootKind.TEST
}

private fun DetachedSourceRootProvenance.hostedProvenance(): WorkspaceSourceRootProvenance =
    when (this) {
        DetachedSourceRootProvenance.AUTHORED -> WorkspaceSourceRootProvenance.AUTHORED
        DetachedSourceRootProvenance.GENERATED -> WorkspaceSourceRootProvenance.GENERATED
    }
