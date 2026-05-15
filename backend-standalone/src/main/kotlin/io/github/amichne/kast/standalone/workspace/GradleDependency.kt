package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.standalone.analysis.PathAsStringSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
internal sealed interface GradleDependency {
    val scope: GradleDependencyScope

    @Serializable
    data class ModuleDependency(
        val targetIdeaModuleName: String,
        override val scope: GradleDependencyScope,
    ) : GradleDependency

    @Serializable
    data class LibraryDependency(
        @Serializable(with = PathAsStringSerializer::class)
        val binaryRoot: Path,
        override val scope: GradleDependencyScope,
    ) : GradleDependency
}
