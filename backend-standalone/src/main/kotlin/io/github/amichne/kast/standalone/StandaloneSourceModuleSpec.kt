package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ModuleName
import java.nio.file.Path

internal data class StandaloneSourceModuleSpec(
    val name: ModuleName,
    val sourceRoots: List<Path>,
    val binaryRoots: List<Path>,
    val dependencyModuleNames: List<ModuleName>,
)
