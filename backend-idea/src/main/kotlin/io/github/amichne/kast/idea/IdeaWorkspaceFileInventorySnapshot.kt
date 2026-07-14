package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain

@ConsistentCopyVisibility
internal data class IdeaWorkspaceFileInventorySnapshot private constructor(
    val kindDomain: WorkspaceFileKindDomain,
    val generation: IdeaWorkspaceInventoryGeneration,
    val modules: List<IdeaWorkspaceModuleSnapshot>,
) {
    private val modulesByIdentity = modules.associateBy(IdeaWorkspaceModuleSnapshot::identity)

    fun module(identity: IdeaWorkspaceModuleIdentity): IdeaWorkspaceModuleSnapshot =
        requireNotNull(modulesByIdentity[identity]) {
            "IDEA workspace inventory does not contain module ${identity.value}"
        }

    companion object {
        fun create(
            kindDomain: WorkspaceFileKindDomain,
            modules: Collection<IdeaWorkspaceModuleSnapshot>,
        ): IdeaWorkspaceFileInventorySnapshot {
            val canonicalModules = modules.sortedBy { module -> module.identity }
            require(canonicalModules.map(IdeaWorkspaceModuleSnapshot::identity).distinct().size == canonicalModules.size) {
                "IDEA workspace inventory module identities must be unique"
            }
            val evidence = buildList {
                add("kind:${kindDomain.name}")
                canonicalModules.forEach { module ->
                    add("module:${module.identity.value}")
                    module.sourceRoots.forEach { add("source-root:$it") }
                    module.contentRoots.forEach { add("content-root:$it") }
                    module.dependencyModuleNames.forEach { add("dependency:$it") }
                    module.filePaths(kindDomain).forEach { add("file:$it") }
                }
            }
            return IdeaWorkspaceFileInventorySnapshot(
                kindDomain = kindDomain,
                generation = IdeaWorkspaceInventoryGeneration.fingerprint(evidence),
                modules = canonicalModules,
            )
        }
    }
}
