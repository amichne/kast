package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.NormalizedPath
import java.nio.file.Path

internal fun normalizeStandalonePath(path: Path): Path = NormalizedPath.of(path).toJavaPath()

internal fun normalizeStandaloneModelPath(path: Path): Path = NormalizedPath.ofAbsolute(path).toJavaPath()

internal fun normalizeStandalonePaths(paths: Iterable<Path>): List<Path> = paths
    .map { NormalizedPath.of(it) }
    .distinct()
    .sorted()
    .map { it.toJavaPath() }
