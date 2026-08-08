package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath

internal fun focusedOwnerModuleNames(
    model: IdeaGradleProjectLoadBridge.GradleWorkspaceModel,
    path: WorkspaceSourcePath,
): Set<IdeaWorkspaceModuleIdentity> {
    val absolutePath = path.absolute.value.toJavaPath()
    return model.moduleAssociations()
        .asSequence()
        .filter { association ->
            association.sourceSets().any { sourceSet ->
                sourceSet.sourceRoots().any { sourceRoot ->
                    absolutePath.startsWith(sourceRoot.path())
                }
            }
        }
        .map { association -> IdeaWorkspaceModuleIdentity.of(association.ideaModuleName()) }
        .toCollection(linkedSetOf())
}
