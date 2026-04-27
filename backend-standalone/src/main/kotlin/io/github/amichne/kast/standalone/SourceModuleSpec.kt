package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ModuleName
import java.nio.file.Path

internal data class SourceModuleSpec(
    val name: ModuleName,
    val sourceRoots: List<Path>,
    val binaryRoots: List<Path>,
    val dependencyModuleNames: List<ModuleName>,
)
