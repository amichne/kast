package io.github.amichne.kast.indexstore.api.index

data class BuildQualifiedGradleSourceSetIdentity(
    val project: BuildQualifiedGradleProjectIdentity,
    val sourceSet: GradleSourceSetName,
)
