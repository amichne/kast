package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity

internal data class IdeaGradleModuleProvenance(
    val ideaModuleIdentity: IdeaWorkspaceModuleIdentity,
    val project: BuildQualifiedGradleProjectIdentity,
    val sourceSets: Set<IdeaGradleSourceSetProvenance>,
)
