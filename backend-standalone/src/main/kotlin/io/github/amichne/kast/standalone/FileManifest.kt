package io.github.amichne.kast.standalone

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

private const val fileManifestSchemaVersion = 1

private val fileManifestJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal class FileManifest(
    workspaceRoot: Path,
    private val enabled: Boolean = true,
    private val json: Json = fileManifestJson,
) {
    internal val manifestPath: Path = kastCacheDirectory(workspaceRoot).resolve("file-manifest.json")

    fun load(): Map<String, Long>? {
        if (!enabled || !Files.isRegularFile(manifestPath)) {
            return null
        }

        val payload = json.decodeFromString<FileManifestPayload>(Files.readString(manifestPath))
        if (payload.schemaVersion != fileManifestSchemaVersion) {
            return null
        }
        return payload.fileLastModifiedMillisByPath
    }

    fun snapshot(sourceRoots: List<Path>): FileManifestSnapshot {
        val previousManifest = load().orEmpty()
        val currentManifest = scanTrackedKotlinFileTimestamps(sourceRoots)
        return FileManifestSnapshot(
            currentPathsByLastModifiedMillis = currentManifest,
            newPaths = (currentManifest.keys - previousManifest.keys).sorted(),
            modifiedPaths = currentManifest.entries
                .asSequence()
                .filter { (path, lastModifiedMillis) -> previousManifest[path]?.let { it != lastModifiedMillis } == true }
                .map(Map.Entry<String, Long>::key)
                .sorted()
                .toList(),
            deletedPaths = (previousManifest.keys - currentManifest.keys).sorted(),
        )
    }

    fun save(currentManifest: Map<String, Long>) {
        if (!enabled) {
            return
        }
        writeCacheFileAtomically(
            path = manifestPath,
            payload = json.encodeToString(
                FileManifestPayload(
                    fileLastModifiedMillisByPath = currentManifest,
                ),
            ),
        )
    }
}

internal data class FileManifestSnapshot(
    val currentPathsByLastModifiedMillis: Map<String, Long>,
    val newPaths: List<String>,
    val modifiedPaths: List<String>,
    val deletedPaths: List<String>,
)

@Serializable
private data class FileManifestPayload(
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
