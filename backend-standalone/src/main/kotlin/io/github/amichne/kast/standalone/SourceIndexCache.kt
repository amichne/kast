package io.github.amichne.kast.standalone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val sourceIndexCacheSchemaVersion = 2

internal class SourceIndexCache(
    workspaceRoot: Path,
    enabled: Boolean = true,
    json: Json = defaultCacheJson,
) : VersionedFileCache<SourceIdentifierIndexCachePayload>(
    schemaVersion = sourceIndexCacheSchemaVersion,
    serializer = SourceIdentifierIndexCachePayload.serializer(),
    enabled = enabled,
    json = json,
) {
    internal val cacheDirectory: Path = kastCacheDirectory(workspaceRoot)
    internal val indexCachePath: Path = cacheDirectory.resolve("source-identifier-index.json")
    private val fileManifest = FileManifest(workspaceRoot = workspaceRoot, enabled = enabled)

    override fun payloadSchemaVersion(payload: SourceIdentifierIndexCachePayload): Int = payload.schemaVersion

    fun save(
        index: MutableSourceIdentifierIndex,
        sourceRoots: List<Path>,
    ) {
        if (!enabled) {
            return
        }
        val manifest = fileManifest.snapshot(sourceRoots).currentPathsByLastModifiedMillis
        val metadata = index.toSerializableMetadata()
        writePayload(
            indexCachePath,
            SourceIdentifierIndexCachePayload(
                candidatePathsByIdentifier = index.toSerializableMap(),
                packageByPath = metadata.packageByPath,
                importsByPath = metadata.importsByPath,
                wildcardImportPackagesByPath = metadata.wildcardImportPackagesByPath,
            ),
        )
        fileManifest.save(manifest)
    }

    fun load(sourceRoots: List<Path>): IncrementalIndexResult? {
        val cachedIndex = readPayload(indexCachePath) ?: return null

        val manifestSnapshot = fileManifest.snapshot(sourceRoots)
        return IncrementalIndexResult(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                candidatePathsByIdentifier = cachedIndex.candidatePathsByIdentifier,
                packageByPath = cachedIndex.packageByPath,
                importsByPath = cachedIndex.importsByPath,
                wildcardImportPackagesByPath = cachedIndex.wildcardImportPackagesByPath,
            ),
            changes = manifestSnapshot.changes,
        )
    }
}

internal data class IncrementalIndexResult(
    val index: MutableSourceIdentifierIndex,
    val changes: FileChangeSet,
) {
    val newPaths: List<String> get() = changes.added
    val modifiedPaths: List<String> get() = changes.modified
    val deletedPaths: List<String> get() = changes.removed
}

@Serializable
internal data class SourceIdentifierIndexCachePayload(
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
