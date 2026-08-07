package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.RelationshipIndexStatus
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.nio.file.Files
import java.nio.file.Path

internal class SourceIndexInventoryStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val fileStore: SourceIndexFileStore,
) {
    private val pathCodec get() = state.pathCodec
    fun saveManifest(entries: Map<String, Long>) {
        val eligibleEntries = buildMap {
            entries.forEach { (rawPath, lastModifiedMillis) ->
                state.sourceFilePolicy.sourcePath(Path.of(rawPath))
                    ?.let { path -> put(path, lastModifiedMillis) }
            }
        }
        state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { conn ->
            val databaseEntries = eligibleEntries.mapKeys { (path, _) -> path.toDatabasePath() }
            mutations.internPathsInTransaction(conn, databaseEntries.keys)
            conn.createStatement().use { stmt -> stmt.execute("DELETE FROM file_manifest") }
            mutations.insertManifestInTransaction(conn, databaseEntries)
            state.removeIneligibleSourceIndexRows(conn)
            state.incrementGenerationInTransaction(conn)
        }
    }

    fun updateManifestEntry(
        path: String,
        lastModifiedMillis: Long,
    ) {
        val sourcePath = state.sourceFilePolicy.sourcePath(Path.of(path)) ?: return
        state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { conn ->
            val databasePath = sourcePath.toDatabasePath()
            mutations.internPathsInTransaction(conn, listOf(databasePath))
            val (prefixId, filename) = pathCodec.encode(databasePath)
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
        }
    }

    fun loadManifest(): Map<String, Long>? {
        if (!state.dbExists()) return null
        return synchronized(state.writeLock) {
            try {
                val conn = state.connection()
                state.loadInterningTables(conn)
                val manifest = state.readTable(SourceIndexReadTable.FILE_MANIFEST)
                buildMap {
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery("SELECT prefix_id, filename, last_modified_millis FROM $manifest")
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
                val manifest = state.readTable(SourceIndexReadTable.FILE_MANIFEST)
                state.connection().createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM $manifest").use { rows ->
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
            val manifest = state.readTable(SourceIndexReadTable.FILE_MANIFEST)
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT prefix_id, filename FROM $manifest")
                buildList {
                    while (rs.next()) {
                        val path = Path.of(pathCodec.decode(rs.getInt(1), rs.getString(2)))
                            .toAbsolutePath()
                            .normalize()
                        if (Files.isRegularFile(path) && state.sourceFilePolicy.isEligible(path)) {
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
            val manifest = state.readTable(SourceIndexReadTable.FILE_MANIFEST)
            val prefixes = state.readTable(SourceIndexReadTable.PATH_PREFIXES)
            val countsByDir = conn.prepareStatement(
                """SELECT prefixes.dir_path, COUNT(*) AS file_count
                   FROM $manifest manifest
                   JOIN $prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
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
                            if (Files.isRegularFile(path) && state.sourceFilePolicy.isEligible(path)) {
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
            val progress = state.readTable(SourceIndexReadTable.MODULE_INDEX_PROGRESS)
            state.connection().prepareStatement(
                "SELECT relationship_index_status FROM $progress WHERE module_name = ?",
            ).use { stmt ->
                stmt.setString(1, moduleName)
                val rs = stmt.executeQuery()
                if (rs.next()) RelationshipIndexStatus.valueOf(rs.getString(1)) else null
            }
        }

    fun moduleIndexStatuses(): Map<String, RelationshipIndexStatus> =
        synchronized(state.writeLock) {
            val progress = state.readTable(SourceIndexReadTable.MODULE_INDEX_PROGRESS)
            state.connection().createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT module_name, relationship_index_status FROM $progress ORDER BY module_name",
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
            val progress = state.readTable(SourceIndexReadTable.MODULE_INDEX_PROGRESS)
            state.connection().createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    """SELECT module_name
                       FROM $progress
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
        val manifest = state.readTable(SourceIndexReadTable.FILE_MANIFEST)
        val prefixes = state.readTable(SourceIndexReadTable.PATH_PREFIXES)
        return """SELECT manifest.prefix_id, manifest.filename
                  FROM $manifest manifest
                  JOIN $prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
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
