package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import java.nio.file.Files
import java.nio.file.Path

internal class SourceIndexInventoryStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val fileStore: SourceIndexFileStore,
) {
    private val pathCodec get() = state.pathCodec
    fun saveManifest(entries: Map<String, Long>) {
        val eligibleEntries = entries.filterKeys(SourceIndexFilePolicy::isEligible)
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                mutations.internPathsInTransaction(conn, eligibleEntries.keys)
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM file_manifest") }
                mutations.insertManifestInTransaction(conn, eligibleEntries)
                state.removeIneligibleSourceIndexRows(conn)
                state.incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun updateManifestEntry(
        path: String,
        lastModifiedMillis: Long,
    ) {
        if (!SourceIndexFilePolicy.isEligible(path)) {
            fileStore.removeFile(path)
            return
        }
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                mutations.internPathsInTransaction(conn, listOf(path))
                val (prefixId, filename) = pathCodec.encode(path)
                conn.prepareStatement(
                    """INSERT OR REPLACE INTO file_manifest (prefix_id, filename, last_modified_millis)
                       VALUES (?, ?, ?)""",
                ).use { stmt ->
                    stmt.setInt(1, prefixId)
                    stmt.setString(2, filename)
                    stmt.setLong(3, lastModifiedMillis)
                    stmt.executeUpdate()
                }
                state.incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun loadManifest(): Map<String, Long>? {
        if (!state.dbExists()) return null
        return synchronized(state.writeLock) {
            try {
                val conn = state.connection()
                state.loadInterningTables(conn)
                buildMap {
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery("SELECT prefix_id, filename, last_modified_millis FROM file_manifest")
                        while (rs.next()) put(pathCodec.decode(rs.getInt(1), rs.getString(2)), rs.getLong(3))
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun knownSourcePaths(): List<Path> {
        if (!state.dbExists()) return emptyList()
        return synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT prefix_id, filename FROM file_manifest")
                buildList {
                    while (rs.next()) {
                        val path = Path.of(pathCodec.decode(rs.getInt(1), rs.getString(2)))
                            .toAbsolutePath()
                            .normalize()
                        if (Files.isRegularFile(path) && SourceIndexFilePolicy.isEligible(path)) {
                            add(path)
                        }
                    }
                }.distinct().sorted()
            }
        }
    }

    fun fileCountBySourceRoot(sourceRoots: Collection<Path>): Map<Path, Int> {
        val roots = normalizedSourceRoots(sourceRoots)
        if (roots.isEmpty()) return emptyMap()
        if (!state.dbExists()) return roots.associateWith { 0 }

        return synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val countsByDir = conn.prepareStatement(
                """SELECT prefixes.dir_path, COUNT(*) AS file_count
                   FROM file_manifest manifest
                   JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
                   GROUP BY prefixes.dir_path""",
            ).use { stmt ->
                val rs = stmt.executeQuery()
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("dir_path"), rs.getInt("file_count"))
                    }
                }
            }
            roots.associateWith { root ->
                val rootDir = sourceRootDirKey(root)
                countsByDir.entries.sumOf { (dir, count) ->
                    if (dirIsWithinSourceRoot(dir, rootDir)) count else 0
                }
            }
        }
    }

    fun filesBySourceRoot(
        sourceRoots: Collection<Path>,
        limitPerRoot: Int? = null,
    ): Map<Path, List<Path>> {
        val roots = normalizedSourceRoots(sourceRoots)
        if (roots.isEmpty()) return emptyMap()
        if (!state.dbExists()) return roots.associateWith { emptyList() }

        return synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            roots.associateWithTo(linkedMapOf()) { root ->
                val rootDir = sourceRootDirKey(root)
                val rows = conn.prepareStatement(sourceRootFilesSql(rootDir, limitPerRoot)).use { stmt ->
                    bindSourceRootPrefix(stmt, rootDir)
                    if (limitPerRoot != null) {
                        stmt.setInt(if (rootDir.isEmpty()) 2 else 3, limitPerRoot)
                    }
                    val rs = stmt.executeQuery()
                    buildList {
                        while (rs.next()) {
                            val path = Path.of(pathCodec.decode(rs.getInt("prefix_id"), rs.getString("filename")))
                                .toAbsolutePath()
                                .normalize()
                            if (Files.isRegularFile(path) && SourceIndexFilePolicy.isEligible(path)) {
                                add(path)
                            }
                        }
                    }
                }
                rows.distinct().sorted()
            }
        }
    }

    fun initializeModuleProgress(modules: Map<String, Int>) {
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM module_index_progress") }
                conn.prepareStatement(
                    """INSERT INTO module_index_progress
                       (module_name, phase2_status, indexed_file_count, total_file_count, last_indexed_epoch_ms)
                       VALUES (?, 'PENDING', 0, ?, NULL)""",
                ).use { stmt ->
                    modules.toSortedMap().forEach { (moduleName, totalFileCount) ->
                        stmt.setString(1, moduleName)
                        stmt.setInt(2, totalFileCount)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun markModuleIndexing(moduleName: String) {
        synchronized(state.writeLock) {
            state.connection().prepareStatement(
                """UPDATE module_index_progress
                   SET phase2_status = 'INDEXING'
                   WHERE module_name = ? AND phase2_status != 'COMPLETE'""",
            ).use { stmt ->
                stmt.setString(1, moduleName)
                stmt.executeUpdate()
            }
        }
    }

    fun markModuleComplete(moduleName: String, fileCount: Int) {
        synchronized(state.writeLock) {
            state.connection().prepareStatement(
                """UPDATE module_index_progress
                   SET phase2_status = 'COMPLETE',
                       indexed_file_count = ?,
                       last_indexed_epoch_ms = ?
                   WHERE module_name = ?""",
            ).use { stmt ->
                stmt.setInt(1, fileCount)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setString(3, moduleName)
                stmt.executeUpdate()
            }
        }
    }

    fun moduleIndexStatus(moduleName: String): String? =
        synchronized(state.writeLock) {
            state.connection().prepareStatement(
                "SELECT phase2_status FROM module_index_progress WHERE module_name = ?",
            ).use { stmt ->
                stmt.setString(1, moduleName)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString(1) else null
            }
        }

    fun completedModules(): Set<String> =
        synchronized(state.writeLock) {
            state.connection().createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT module_name FROM module_index_progress WHERE phase2_status = 'COMPLETE'",
                )
                buildSet {
                    while (rs.next()) {
                        add(rs.getString(1))
                    }
                }
            }
        }

    private fun normalizedSourceRoots(sourceRoots: Collection<Path>): List<Path> =
        sourceRoots
            .map { root -> root.toAbsolutePath().normalize() }
            .distinct()
            .sorted()

    private fun sourceRootDirKey(root: Path): String =
        pathCodec.decompose(root.resolve(SqliteSourceIndexStoreState.sourceRootProbeFileName).toString()).first

    private fun dirIsWithinSourceRoot(
        dir: String,
        sourceRootDir: String,
    ): Boolean = when {
        sourceRootDir.isEmpty() -> !dir.startsWith(SqliteSourceIndexStoreState.absolutePathPrefix)
        dir == sourceRootDir -> true
        else -> dir.startsWith("$sourceRootDir/")
    }

    private fun sourceRootFilesSql(
        sourceRootDir: String,
        limitPerRoot: Int?,
    ): String {
        val rootClause = if (sourceRootDir.isEmpty()) {
            "prefixes.dir_path NOT LIKE ?"
        } else {
            "(prefixes.dir_path = ? OR prefixes.dir_path LIKE ?)"
        }
        val limitClause = if (limitPerRoot == null) "" else " LIMIT ?"
        return """SELECT manifest.prefix_id, manifest.filename
                  FROM file_manifest manifest
                  JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
                  WHERE $rootClause
                  ORDER BY prefixes.dir_path, manifest.filename$limitClause"""
    }

    private fun bindSourceRootPrefix(
        stmt: java.sql.PreparedStatement,
        sourceRootDir: String,
    ) {
        if (sourceRootDir.isEmpty()) {
            stmt.setString(1, "${SqliteSourceIndexStoreState.absolutePathPrefix}%")
        } else {
            stmt.setString(1, sourceRootDir)
            stmt.setString(2, "$sourceRootDir/%")
        }
    }
}
