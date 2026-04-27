package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.NormalizedPath
import java.nio.file.Path

internal fun normalizePath(path: Path): Path = NormalizedPath.of(path).toJavaPath()

internal fun normalizeModelPath(path: Path): Path = NormalizedPath.ofAbsolute(path).toJavaPath()

internal fun normalizePaths(paths: Iterable<Path>): List<Path> = paths
    .map { NormalizedPath.of(it) }
    .distinct()
    .sorted()
    .map { it.toJavaPath() }
