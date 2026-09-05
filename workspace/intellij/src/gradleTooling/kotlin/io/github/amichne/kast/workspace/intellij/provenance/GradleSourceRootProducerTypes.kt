package io.github.amichne.kast.workspace.intellij.provenance

/** Gradle's producer-owned classification for one exact IDEA source directory. */
enum class GradleSourceRootProducerProvenance {
    AUTHORED,
    GENERATED,
}

/** Gradle's producer-owned role for one exact source-set directory. */
enum class GradleSourceRootProducerRole {
    CODE,
    RESOURCE,
}
