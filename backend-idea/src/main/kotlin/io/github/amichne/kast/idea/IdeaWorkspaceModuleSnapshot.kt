package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain

@ConsistentCopyVisibility
internal data class IdeaWorkspaceModuleSnapshot private constructor(
    val identity: IdeaWorkspaceModuleIdentity,
    val sourceRoots: List<String>,
    val contentRoots: List<String>,
    val dependencyModuleNames: List<String>,
    val sourceFilePaths: List<String>,
    val scriptFilePaths: List<String>,
) {
    val allFilePaths: List<String> = (sourceFilePaths + scriptFilePaths).distinct().sorted()

    fun filePaths(kindDomain: WorkspaceFileKindDomain): List<String> = when (kindDomain) {
        WorkspaceFileKindDomain.SOURCE_ONLY -> sourceFilePaths
        WorkspaceFileKindDomain.SCRIPT_ONLY -> scriptFilePaths
        WorkspaceFileKindDomain.MIXED -> allFilePaths
    }

    companion object {
        fun create(
            identity: IdeaWorkspaceModuleIdentity,
            sourceRoots: Collection<String>,
            contentRoots: Collection<String>,
            dependencyModuleNames: Collection<String>,
            sourceFilePaths: Collection<String>,
            scriptFilePaths: Collection<String>,
        ): IdeaWorkspaceModuleSnapshot = IdeaWorkspaceModuleSnapshot(
            identity = identity,
            sourceRoots = sourceRoots.canonicalAbsolutePaths("source root"),
            contentRoots = contentRoots.canonicalAbsolutePaths("content root"),
            dependencyModuleNames = dependencyModuleNames
                .filter(String::isNotBlank)
                .filterNot { dependencyName -> dependencyName == identity.value }
                .distinct()
                .sorted(),
            sourceFilePaths = sourceFilePaths.canonicalAbsolutePaths("source file"),
            scriptFilePaths = scriptFilePaths.canonicalAbsolutePaths("script file"),
        )

        private fun Collection<String>.canonicalAbsolutePaths(label: String): List<String> =
            map { path ->
                require(path.isNotBlank()) { "IDEA workspace $label path must be nonblank" }
                path
            }.distinct().sorted()
    }
}
