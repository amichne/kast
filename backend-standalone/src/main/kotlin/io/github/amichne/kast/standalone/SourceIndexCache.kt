package io.github.amichne.kast.standalone

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val sourceIndexCacheSchemaVersion = 2

private val sourceIndexCacheJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal class SourceIndexCache(
    workspaceRoot: Path,
    private val enabled: Boolean = true,
    private val json: Json = sourceIndexCacheJson,
) {
    internal val cacheDirectory: Path = kastCacheDirectory(workspaceRoot)
    internal val indexCachePath: Path = cacheDirectory.resolve("source-identifier-index.json")
    private val fileManifest = FileManifest(workspaceRoot = workspaceRoot, enabled = enabled)

    fun save(
        index: MutableSourceIdentifierIndex,
        sourceRoots: List<Path>,
    ) {
        if (!enabled) {
            return
        }
        val manifest = fileManifest.snapshot(sourceRoots).currentPathsByLastModifiedMillis
        val metadata = index.toSerializableMetadata()
        writeCacheFileAtomically(
            path = indexCachePath,
            payload = json.encodeToString(
                SourceIdentifierIndexCachePayload(
                    candidatePathsByIdentifier = index.toSerializableMap(),
                    packageByPath = metadata.packageByPath,
                    importsByPath = metadata.importsByPath,
                    wildcardImportPackagesByPath = metadata.wildcardImportPackagesByPath,
                ),
            ),
        )
        fileManifest.save(manifest)
    }

    fun load(sourceRoots: List<Path>): IncrementalIndexResult? {
        if (!enabled) {
            return null
        }
        if (!Files.isRegularFile(indexCachePath)) {
            return null
        }

        val cachedIndex = json.decodeFromString<SourceIdentifierIndexCachePayload>(Files.readString(indexCachePath))
        if (cachedIndex.schemaVersion != sourceIndexCacheSchemaVersion) {
            return null
        }

        val manifestSnapshot = fileManifest.snapshot(sourceRoots)
        return IncrementalIndexResult(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                candidatePathsByIdentifier = cachedIndex.candidatePathsByIdentifier,
                packageByPath = cachedIndex.packageByPath,
                importsByPath = cachedIndex.importsByPath,
                wildcardImportPackagesByPath = cachedIndex.wildcardImportPackagesByPath,
            ),
            newPaths = manifestSnapshot.newPaths,
            modifiedPaths = manifestSnapshot.modifiedPaths,
            deletedPaths = manifestSnapshot.deletedPaths,
        )
    }
}

internal data class IncrementalIndexResult(
    val index: MutableSourceIdentifierIndex,
    val newPaths: List<String>,
    val modifiedPaths: List<String>,
    val deletedPaths: List<String>,
)

@Serializable
private data class SourceIdentifierIndexCachePayload(
    val schemaVersion: Int = sourceIndexCacheSchemaVersion,
    val candidatePathsByIdentifier: Map<String, List<String>>,
    val packageByPath: Map<String, String> = emptyMap(),
    val importsByPath: Map<String, List<String>> = emptyMap(),
    val wildcardImportPackagesByPath: Map<String, List<String>> = emptyMap(),
)

internal fun kastCacheDirectory(workspaceRoot: Path): Path = workspaceRoot.resolve(".kast").resolve("cache")

internal fun writeCacheFileAtomically(
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
