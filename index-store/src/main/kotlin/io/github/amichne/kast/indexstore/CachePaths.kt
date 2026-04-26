package io.github.amichne.kast.indexstore

import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Default JSON configuration shared by cache payloads.
 */
val defaultCacheJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun kastGradleDirectory(workspaceRoot: Path): Path = workspaceRoot.resolve(".gradle").resolve("kast")

fun kastCacheDirectory(workspaceRoot: Path): Path = kastGradleDirectory(workspaceRoot).resolve("cache")

fun sourceIndexDatabasePath(workspaceRoot: Path): Path = kastCacheDirectory(workspaceRoot).resolve("source-index.db")

fun writeCacheFileAtomically(
    path: Path,
    payload: String,
) {
    val parent = requireNotNull(path.parent) { "Cache path must have a parent directory: $path" }
    Files.createDirectories(parent)
    val tempFile = Files.createTempFile(parent, "${path.fileName}.tmp-", null)
    try {
        Files.writeString(tempFile, payload)
        try {
            Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tempFile)
    }
}
