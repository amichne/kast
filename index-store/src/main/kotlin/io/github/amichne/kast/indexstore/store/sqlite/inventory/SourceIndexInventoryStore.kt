package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.RelationshipIndexStatus
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

    fun manifestFileCount(): Int? {
        if (!state.dbExists()) return null
        return synchronized(state.writeLock) {
            try {
                state.connection().createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM file_manifest").use { rows ->
                        if (rows.next()) rows.getInt(1) else 0
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

    fun moduleIndexStatus(moduleName: String): RelationshipIndexStatus? =
        synchronized(state.writeLock) {
            state.connection().prepareStatement(
                "SELECT relationship_index_status FROM module_index_progress WHERE module_name = ?",
            ).use { stmt ->
                stmt.setString(1, moduleName)
                val rs = stmt.executeQuery()
                if (rs.next()) RelationshipIndexStatus.valueOf(rs.getString(1)) else null
            }
        }

    fun moduleIndexStatuses(): Map<String, RelationshipIndexStatus> =
        synchronized(state.writeLock) {
            state.connection().createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT module_name, relationship_index_status FROM module_index_progress ORDER BY module_name",
                )
                buildMap {
                    while (rs.next()) {
                        put(rs.getString(1), RelationshipIndexStatus.valueOf(rs.getString(2)))
                    }
                }
            }
        }

    fun completedModules(): Set<String> =
        synchronized(state.writeLock) {
            state.connection().createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    """SELECT module_name
                       FROM module_index_progress
                       WHERE relationship_index_status IN ('COMPLETE','DEGRADED')""",
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
