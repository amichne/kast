package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.*
import io.github.amichne.kast.indexstore.store.cache.defaultCacheJson
import java.nio.file.Files
import java.nio.file.Path

internal class SourceIndexSnapshotStore(
    private val state: SqliteSourceIndexStoreState,
) {
    private val dbPath get() = state.dbPath
    fun readWorkspaceDiscovery(cacheKey: String): String? {
        synchronized(state.writeLock) {
            val conn = state.connection()
            return conn.prepareStatement(
                "SELECT payload FROM workspace_discovery WHERE cache_key = ?",
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    fun writeWorkspaceDiscovery(cacheKey: String, schemaVersion: Int, payload: String) {
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.prepareStatement(
                "INSERT OR REPLACE INTO workspace_discovery (cache_key, schema_version, payload) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                stmt.setInt(2, schemaVersion)
                stmt.setString(3, payload)
                stmt.executeUpdate()
            }
        }
    }

    fun readGeneration(): SourceIndexGeneration {
        synchronized(state.writeLock) {
            val conn = state.connection()
            return try {
                state.readGenerationInTransaction(conn)
            } catch (_: Exception) {
                SourceIndexGeneration(0)
            }
        }
    }

    fun exportSnapshotDatabase(
        target: Path,
        treeOid: GitObjectId,
        producerVersion: ProducerVersion,
    ): PublicationEvidence = synchronized(state.writeLock) {
        require(!Files.exists(target)) { "Snapshot export target already exists: $target" }
        Files.createDirectories(target.toAbsolutePath().normalize().parent)
        val conn = state.connection()
        val generationBefore = state.readGenerationInTransaction(conn).value
        val (moduleProgressCount, incompleteModuleCount) = conn.createStatement().use { statement ->
            val result = statement.executeQuery(
                """SELECT COUNT(*) AS total,
                          SUM(CASE WHEN relationship_index_status != 'COMPLETE' OR indexed_file_count != total_file_count
                                   THEN 1 ELSE 0 END) AS incomplete
                   FROM module_index_progress""",
            )
            check(result.next())
            result.getInt("total") to result.getInt("incomplete")
        }
        val pendingCount = conn.createStatement().use { statement ->
            val result = statement.executeQuery("SELECT COUNT(*) FROM pending_updates WHERE applied = 0")
            check(result.next())
            result.getInt(1)
        }
        val escapedTarget = target.toAbsolutePath().normalize().toString().replace("'", "''")
        conn.createStatement().use { statement -> statement.execute("VACUUM INTO '$escapedTarget'") }
        val generationAfter = state.readGenerationInTransaction(conn).value
        PublicationEvidence(
            generationBefore = generationBefore,
            generationAfter = generationAfter,
            moduleProgressCount = moduleProgressCount,
            incompleteModuleCount = incompleteModuleCount,
            pendingCount = pendingCount,
            treeOid = treeOid,
            indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
            producerVersion = producerVersion,
        )
    }

    fun readHeadCommit(): String? {
        synchronized(state.writeLock) {
            val conn = state.connection()
            return try {
                conn.prepareStatement("SELECT head_commit FROM schema_version LIMIT 1").use { stmt ->
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getString(1) else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun writeHeadCommit(sha: String) {
        synchronized(state.writeLock) {
            state.connection().prepareStatement("UPDATE schema_version SET head_commit = ?").use { stmt ->
                stmt.setString(1, sha)
                stmt.executeUpdate()
            }
        }
    }

}
