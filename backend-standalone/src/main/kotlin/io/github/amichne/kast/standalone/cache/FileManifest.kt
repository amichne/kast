package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.standalone.normalizeStandalonePath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

internal data class FileManifestSnapshot(
    val currentPathsByLastModifiedMillis: Map<String, Long>,
    val changes: FileChangeSet,
) {
    val newPaths: List<String> get() = changes.added
    val modifiedPaths: List<String> get() = changes.modified
    val deletedPaths: List<String> get() = changes.removed
}

internal fun scanTrackedKotlinFileTimestamps(sourceRoots: List<Path>): Map<String, Long> = buildMap {
    sourceRoots
        .distinct()
        .sorted()
        .forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) {
                return@forEach
            }

            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .forEach { file ->
                        put(
                            normalizeStandalonePath(file).toString(),
                            Files.getLastModifiedTime(file).toMillis(),
                        )
                    }
            }
        }
}
