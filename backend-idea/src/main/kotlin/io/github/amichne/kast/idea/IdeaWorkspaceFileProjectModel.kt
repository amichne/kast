package io.github.amichne.kast.idea

import java.nio.file.Path

internal data class IdeaWorkspaceFileProjectModel(
    val modules: List<Module>,
    val linkedBuildRoots: List<Path>,
    val moduleAssociations: List<GradleModuleAssociation>,
    val rootGradleScriptPaths: Set<Path>,
) {
    data class Module(
        val identity: IdeaWorkspaceModuleIdentity,
        val sourceRoots: List<Path>,
        val contentRoots: List<Path>,
        val dependencyModuleNames: List<IdeaWorkspaceModuleIdentity>,
        val ownedFilePaths: List<Path>,
    )

    data class GradleModuleAssociation(
        val moduleIdentity: IdeaWorkspaceModuleIdentity,
        val linkedBuildRoot: Path,
        val rootModule: Boolean,
    )
}
