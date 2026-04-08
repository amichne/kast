package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.standalone.normalizeStandalonePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

private const val fileManifestSchemaVersion = 1

internal class FileManifest(
    workspaceRoot: Path,
    enabled: Boolean = true,
    json: Json = defaultCacheJson,
) : VersionedFileCache<FileManifestPayload>(
    schemaVersion = fileManifestSchemaVersion,
    serializer = FileManifestPayload.serializer(),
    enabled = enabled,
    json = json,
) {
    internal val manifestPath: Path = kastCacheDirectory(workspaceRoot).resolve("file-manifest.json")

    override fun payloadSchemaVersion(payload: FileManifestPayload): Int = payload.schemaVersion

    fun load(): Map<String, Long>? {
        val payload = readPayload(manifestPath) ?: return null
        return payload.fileLastModifiedMillisByPath
    }

    fun snapshot(sourceRoots: List<Path>): FileManifestSnapshot {
        val previousManifest = load().orEmpty()
        val currentManifest = scanTrackedKotlinFileTimestamps(sourceRoots)
        return FileManifestSnapshot(
            currentPathsByLastModifiedMillis = currentManifest,
            changes = FileChangeSet(
                added = (currentManifest.keys - previousManifest.keys).sorted(),
                modified = currentManifest.entries
                    .asSequence()
                    .filter { (path, lastModifiedMillis) -> previousManifest[path]?.let { it != lastModifiedMillis } == true }
                    .map(Map.Entry<String, Long>::key)
                    .sorted()
                    .toList(),
                removed = (previousManifest.keys - currentManifest.keys).sorted(),
            ),
        )
    }

    fun save(currentManifest: Map<String, Long>) {
        writePayload(
            manifestPath,
            FileManifestPayload(fileLastModifiedMillisByPath = currentManifest),
        )
    }
}

internal data class FileManifestSnapshot(
    val currentPathsByLastModifiedMillis: Map<String, Long>,
    val changes: FileChangeSet,
) {
    /** Alias for backward compatibility with callers that accessed newPaths directly. */
    val newPaths: List<String> get() = changes.added
    val modifiedPaths: List<String> get() = changes.modified
    val deletedPaths: List<String> get() = changes.removed
}

@Serializable
internal data class FileManifestPayload(
    val schemaVersion: Int = fileManifestSchemaVersion,
    val fileLastModifiedMillisByPath: Map<String, Long>,
)

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
