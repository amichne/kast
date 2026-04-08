package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.standalone.normalizeStandaloneModelPath
import java.nio.file.Files
import java.nio.file.Path

internal class ToolingApiPathNormalizer(
    private val pathExists: (Path) -> Boolean = Files::exists,
) {
    private val pathExistsCache = linkedMapOf<Path, Boolean>()

    fun normalizeExistingSourceRoots(paths: Sequence<Path>): List<Path> = paths
        .map(::normalizeStandaloneModelPath)
        .distinct()
        .filter(::exists)
        .toList()
        .sorted()

    private fun exists(path: Path): Boolean = pathExistsCache.getOrPut(path) {
        pathExists(path)
    }
}
