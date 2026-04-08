package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.api.NormalizedPath
import io.github.amichne.kast.standalone.MutableSourceIdentifierIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val sourceIndexCacheSchemaVersion = 3

/**
 * Persists the source identifier index and file manifest to a SQLite database.
 *
 * On first startup after the migration the class reads the old JSON files
 * (`source-identifier-index.json` + `file-manifest.json`), writes their
 * content to SQLite in a single transaction, and deletes the JSON files.
 * Subsequent startups use SQLite directly.
 */
internal class SourceIndexCache(
    workspaceRoot: Path,
    private val enabled: Boolean = true,
    private val json: Json = defaultCacheJson,
) : AutoCloseable {
    private val cacheDirectory: Path = kastCacheDirectory(workspaceRoot)
    private val indexCachePath: Path = cacheDirectory.resolve("source-identifier-index.json")
    private val store = SqliteSourceIndexStore(workspaceRoot)

    // Kept only to read `manifestPath` during JSON migration.
    private val legacyManifest = FileManifest(workspaceRoot = workspaceRoot, enabled = enabled, json = json)

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
     * Loads the index from SQLite (migrating from JSON on first run), or returns
     * `null` when no cached data is available and a full build is required.
     */
    fun load(sourceRoots: List<Path>): IncrementalIndexResult? {
        if (!enabled) return null

        if (store.dbExists()) {
            val schemaValid = store.ensureSchema()
            // Schema was rebuilt (version mismatch) — treat as a cache miss so the
            // caller rebuilds the index from source files.
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

        // Migrate from the legacy JSON files, if present.
        val jsonPayload = readJsonPayload() ?: return null
        val previousManifest = legacyManifest.load().orEmpty()
        val currentManifest = scanTrackedKotlinFileTimestamps(sourceRoots)
        val changes = buildChangeSet(current = currentManifest, previous = previousManifest)

        store.ensureSchema()
        store.saveFullIndex(updates = jsonPayloadToUpdates(jsonPayload), manifest = previousManifest)
        deleteJsonFiles()

        return IncrementalIndexResult(
            index = MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(
                candidatePathsByIdentifier = jsonPayload.candidatePathsByIdentifier,
                moduleNameByPath = jsonPayload.moduleNameByPath,
                packageByPath = jsonPayload.packageByPath,
                importsByPath = jsonPayload.importsByPath,
                wildcardImportPackagesByPath = jsonPayload.wildcardImportPackagesByPath,
            ),
            changes = changes,
        )
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

    private fun jsonPayloadToUpdates(payload: SourceIdentifierIndexCachePayload): List<FileIndexUpdate> {
        val identifiersByPath = mutableMapOf<String, MutableSet<String>>()
        payload.candidatePathsByIdentifier.forEach { (identifier, paths) ->
            paths.forEach { path -> identifiersByPath.getOrPut(path) { mutableSetOf() }.add(identifier) }
        }
        val allPaths = (identifiersByPath.keys + payload.packageByPath.keys + payload.moduleNameByPath.keys)
            .toHashSet()
        return allPaths.map { path ->
            FileIndexUpdate(
                path = path,
                identifiers = identifiersByPath[path].orEmpty(),
                packageName = payload.packageByPath[path],
                moduleName = payload.moduleNameByPath[path],
                imports = payload.importsByPath[path].orEmpty().toSet(),
                wildcardImports = payload.wildcardImportPackagesByPath[path].orEmpty().toSet(),
            )
        }
    }

    private fun readJsonPayload(): SourceIdentifierIndexCachePayload? {
        if (!Files.isRegularFile(indexCachePath)) return null
        return try {
            val payload = json.decodeFromString(
                SourceIdentifierIndexCachePayload.serializer(),
                Files.readString(indexCachePath),
            )
            if (payload.schemaVersion != sourceIndexCacheSchemaVersion) null else payload
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteJsonFiles() {
        runCatching { Files.deleteIfExists(indexCachePath) }
        runCatching { Files.deleteIfExists(legacyManifest.manifestPath) }
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
    val moduleNameByPath: Map<String, String> = emptyMap(),
    val packageByPath: Map<String, String> = emptyMap(),
    val importsByPath: Map<String, List<String>> = emptyMap(),
    val wildcardImportPackagesByPath: Map<String, List<String>> = emptyMap(),
)

internal fun kastGradleDirectory(workspaceRoot: Path): Path = workspaceRoot.resolve(".gradle").resolve("kast")

internal fun kastCacheDirectory(workspaceRoot: Path): Path = kastGradleDirectory(workspaceRoot).resolve("cache")

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

