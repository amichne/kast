package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import java.nio.file.Path

internal data class IdeaGradleModuleProvenance(
    val project: BuildQualifiedGradleProjectIdentity,
    val projectDirectory: Path,
    val sourceSets: Set<IdeaGradleSourceSetProvenance>,
) {
    init {
        require(projectDirectory.isAbsolute) { "Model-proven Gradle project directory must be absolute" }
    }
}
