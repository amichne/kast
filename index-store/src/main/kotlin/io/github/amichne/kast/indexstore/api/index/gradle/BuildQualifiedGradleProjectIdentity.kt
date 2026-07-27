package io.github.amichne.kast.indexstore.api.index

data class BuildQualifiedGradleProjectIdentity(
    val buildRoot: WorkspaceRelativeGradleBuildRoot,
    val projectPath: GradleProjectPath,
)
