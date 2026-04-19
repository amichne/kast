package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.standalone.MutableSourceIdentifierIndex
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Default JSON configuration shared by caches.
 */
internal val defaultCacheJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Persists the source identifier index and file manifest to a SQLite database.
 */
internal class SourceIndexCache(
    workspaceRoot: Path,
    private val enabled: Boolean = true,
) : AutoCloseable {
    internal val store = SqliteSourceIndexStore(workspaceRoot)

    /** Full save: replaces all SQLite data in one transaction. */
    fun save(
        index: MutableSourceIdentifierIndex,
        sourceRoots: List<Path>,
    ) {
        if (!enabled) return
        store.ensureSchema()
        val manifest = scanTrackedKotlinFileTimestamps(sourceRoots)
        store.saveFullIndex(updates = indexToUpdates(index), manifest = manifest)
    }

    /**
     * Loads the index from SQLite, or returns `null` when no cached data is
     * available and a full build is required.
     */
    fun load(sourceRoots: List<Path>): IncrementalIndexResult? {
        if (!enabled) return null

        if (!store.dbExists()) return null

        val schemaValid = store.ensureSchema()
        if (!schemaValid) return null

        val manifestSnapshot = makeManifestSnapshot(sourceRoots)
        return try {
            IncrementalIndexResult(
                index = store.loadFullIndex(),
                changes = manifestSnapshot.changes,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Incrementally writes a single file's index data to SQLite.
     * No-op if the database has not been initialised yet (the next full
     * [save] will capture the data).
     */
    fun saveFileIndex(
        index: MutableSourceIdentifierIndex,
        normalizedPath: NormalizedPath,
    ) {
        if (!enabled || !store.dbExists()) return
        runCatching {
            store.saveFileIndex(
                FileIndexUpdate(
                    path = normalizedPath.value,
                    identifiers = index.identifiersForPath(normalizedPath).map { it.value }.toSet(),
                    packageName = index.packageNameForPath(normalizedPath)?.value,
                    moduleName = index.moduleNameForPath(normalizedPath)?.value,
                    imports = index.importsForPath(normalizedPath).map { it.value }.toSet(),
                    wildcardImports = index.wildcardImportsForPath(normalizedPath).map { it.value }.toSet(),
                ),
            )
        }
    }

    /** Incrementally removes a single file's rows from all SQLite tables. */
    fun saveRemovedFile(path: String) {
        if (!enabled || !store.dbExists()) return
        runCatching { store.removeFile(path) }
    }

    override fun close() {
        store.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeManifestSnapshot(sourceRoots: List<Path>): FileManifestSnapshot {
        val current = scanTrackedKotlinFileTimestamps(sourceRoots)
        val previous = store.loadManifest().orEmpty()
        return FileManifestSnapshot(
            currentPathsByLastModifiedMillis = current,
            changes = buildChangeSet(current = current, previous = previous),
        )
    }

    private fun buildChangeSet(
        current: Map<String, Long>,
        previous: Map<String, Long>,
    ): FileChangeSet = FileChangeSet(
        added = (current.keys - previous.keys).sorted(),
        modified = current.entries
            .filter { (path, millis) -> previous[path]?.let { it != millis } == true }
            .map { it.key }
            .sorted(),
        removed = (previous.keys - current.keys).sorted(),
    )

    private fun indexToUpdates(index: MutableSourceIdentifierIndex): List<FileIndexUpdate> {
        val metadata = index.toSerializableMetadata()
        val identifiersByPath = mutableMapOf<String, MutableSet<String>>()
        index.toSerializableMap().forEach { (identifier, paths) ->
            paths.forEach { path -> identifiersByPath.getOrPut(path) { mutableSetOf() }.add(identifier) }
        }
        val allPaths = (identifiersByPath.keys + metadata.packageByPath.keys + metadata.moduleNameByPath.keys)
            .toHashSet()
        return allPaths.map { path ->
            FileIndexUpdate(
                path = path,
                identifiers = identifiersByPath[path].orEmpty(),
                packageName = metadata.packageByPath[path],
                moduleName = metadata.moduleNameByPath[path],
                imports = metadata.importsByPath[path].orEmpty().toSet(),
                wildcardImports = metadata.wildcardImportPackagesByPath[path].orEmpty().toSet(),
            )
        }
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

internal fun kastGradleDirectory(workspaceRoot: Path): Path = workspaceRoot.resolve(".gradle").resolve("kast")

internal fun kastCacheDirectory(workspaceRoot: Path): Path = kastGradleDirectory(workspaceRoot).resolve("cache")

internal fun writeCacheFileAtomically(
    path: Path,
    payload: String,
) {
    val parent = requireNotNull(path.parent) { "Cache path must have a parent directory: $path" }
    java.nio.file.Files.createDirectories(parent)
    val tempFile = java.nio.file.Files.createTempFile(parent, "${path.fileName}.tmp-", null)
    try {
        java.nio.file.Files.writeString(tempFile, payload)
        try {
            java.nio.file.Files.move(tempFile, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        java.nio.file.Files.deleteIfExists(tempFile)
    }
}
