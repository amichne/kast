package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.FileIndexUpdate
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.standalone.MutableSourceIdentifierIndex
import java.nio.file.Path

/**
 * Persists the source identifier index and file manifest to a SQLite database.
 */
internal class SourceIndexCache(
    workspaceRoot: Path,
    private val enabled: Boolean = true,
    private val gitDeltaChangeDetector: GitDeltaCandidateDetector = GitDeltaChangeDetector(workspaceRoot),
    private val lastModifiedMillis: (Path) -> Long = { path ->
        java.nio.file.Files.getLastModifiedTime(path)
            .toMillis()
    },
) : AutoCloseable {
    internal val store = SqliteSourceIndexStore(workspaceRoot)

    /** Full save: replaces all SQLite data in one transaction. */
    fun save(
        index: MutableSourceIdentifierIndex,
        sourceRoots: List<Path>,
        headCommit: String? = gitDeltaChangeDetector.currentHeadCommit(),
    ) {
        if (!enabled) return
        store.ensureSchema()
        val manifest = scanTrackedKotlinFileTimestamps(sourceRoots)
        store.saveFullIndex(updates = indexToUpdates(index), manifest = manifest)
        store.reconcilePendingUpdates()
        (headCommit ?: gitDeltaChangeDetector.currentHeadCommit())?.let(store::writeHeadCommit)
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

        store.reconcilePendingUpdates()
        val manifestSnapshot = makeManifestSnapshot(sourceRoots)
        return try {
            IncrementalIndexResult(
                index = MutableSourceIdentifierIndex.fromSourceIndexSnapshot(store.loadSourceIndexSnapshot()),
                changes = manifestSnapshot.changes,
                headCommit = manifestSnapshot.headCommit,
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
            val filePath = normalizedPath.toJavaPath()
            if (java.nio.file.Files.isRegularFile(filePath)) {
                store.updateManifestEntry(normalizedPath.value, lastModifiedMillis(filePath))
            }
        }
    }

    /** Incrementally removes a single file's rows from all SQLite tables. */
    fun saveRemovedFile(path: String) {
        if (!enabled || !store.dbExists()) return
        runCatching {
            store.removeFile(path)
        }
    }

    override fun close() {
        store.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeManifestSnapshot(sourceRoots: List<Path>): FileManifestSnapshot {
        val previous = store.loadManifest().orEmpty()
        val candidates = gitDeltaChangeDetector.detectCandidatePaths(store.readHeadCommit(), sourceRoots)
        val current = candidates
                          ?.let { scanCandidateTimestamps(sourceRoots, previous, it) }
                      ?: scanTrackedKotlinFileTimestamps(sourceRoots)
        return FileManifestSnapshot(
            currentPathsByLastModifiedMillis = current,
            changes = buildChangeSet(current = current, previous = previous),
            headCommit = candidates?.headCommit,
        )
    }

    private fun scanCandidateTimestamps(
        sourceRoots: List<Path>,
        previous: Map<String, Long>,
        candidates: GitDeltaCandidates,
    ): Map<String, Long> {
        val currentPaths = scanTrackedKotlinFilePaths(sourceRoots)
        val pathsToStat = candidates.paths + (currentPaths - previous.keys) + (previous.keys - candidates.trackedPaths)
        return currentPaths.associateWith { path ->
            if (path in pathsToStat) {
                lastModifiedMillis(Path.of(path))
            } else {
                previous.getValue(path)
            }
        }
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
    val headCommit: String?,
) {
    val newPaths: List<String> get() = changes.added
    val modifiedPaths: List<String> get() = changes.modified
    val deletedPaths: List<String> get() = changes.removed
}
