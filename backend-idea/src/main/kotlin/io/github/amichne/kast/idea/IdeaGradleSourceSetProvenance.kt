package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import java.nio.file.Path

internal data class IdeaGradleSourceSetProvenance(
    val name: GradleSourceSetName,
    val sourceRoots: Set<Path>,
) {
    init {
        require(sourceRoots.isNotEmpty()) { "Model-proven Gradle source set must own at least one source root" }
        require(sourceRoots.all(Path::isAbsolute)) { "Model-proven Gradle source roots must be absolute" }
    }
}
