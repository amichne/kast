package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.*
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.store.cache.defaultCacheJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.nio.file.Path
import java.sql.Connection

internal class SourceIndexPendingUpdateStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val referenceStore: SourceIndexReferenceStore,
) {
    private val workspaceRoot get() = state.workspaceRoot
    private val pathCodec get() = state.pathCodec
    private val fqCodec get() = state.fqCodec
    fun appendPendingUpdate(
        op: String,
        path: String,
        payload: String?,
        sessionId: String? = null,
    ) {
        synchronized(state.writeLock) {
            val conn = state.connection()
            val (prefixId, filename) = pathCodec.encodeOrCreate(conn, path)
            conn.prepareStatement(
                """INSERT INTO pending_updates (op, prefix_id, filename, payload, session_id, epoch_ms)
                   VALUES (?, ?, ?, ?, ?, ?)""",
            ).use { stmt ->
                stmt.setString(1, op)
                stmt.setInt(2, prefixId)
                stmt.setString(3, filename)
                stmt.setString(4, payload)
                stmt.setString(5, sessionId)
                stmt.setLong(6, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    fun reconcilePendingUpdates(): Int {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            conn.autoCommit = false
            return try {
                val pending = readLatestPendingUpdates(conn)
                for (update in pending) {
                    applyPendingUpdate(conn, update)
                }
                markPendingUpdatesApplied(conn, pending)
                cleanupAppliedPendingUpdates(conn)
                if (pending.isNotEmpty()) state.incrementGenerationInTransaction(conn)
                state.commitManifestMutation(conn)
                pending.size
            } catch (e: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun readLatestPendingUpdates(conn: Connection): List<PendingUpdateRow> =
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                """SELECT p.seq, p.op, p.prefix_id, p.filename, p.payload
                   FROM pending_updates p
                   INNER JOIN (
                       SELECT prefix_id, filename, MAX(seq) AS max_seq
                       FROM pending_updates
                       WHERE applied = 0
                       GROUP BY prefix_id, filename
                   ) latest ON p.seq = latest.max_seq
                   ORDER BY p.seq""",
            )
            buildList {
                while (rs.next()) {
                    add(
                        PendingUpdateRow(
                            seq = rs.getLong(1),
                            op = rs.getString(2),
                            prefixId = rs.getInt(3),
                            filename = rs.getString(4),
                            payload = rs.getString(5),
                        ),
                    )
                }
            }
        }

    private fun applyPendingUpdate(
        conn: Connection,
        update: PendingUpdateRow,
    ) {
        val path = pathCodec.decode(update.prefixId, update.filename)
        if (!SourceIndexFilePolicy.isEligible(path)) {
            mutations.deleteFileRowsInTransaction(conn, update.prefixId, update.filename)
            return
        }
        when (update.op) {
            "upsert_file" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingFilePayload.serializer(),
                    requireNotNull(update.payload)
                )
                val fileUpdate = FileIndexUpdate(
                    path = path,
                    identifiers = payload.identifiers.toSet(),
                    packageName = payload.packageName,
                    modulePath = payload.modulePath,
                    sourceSet = payload.sourceSet,
                    imports = payload.imports.toSet(),
                    wildcardImports = payload.wildcardImports.toSet(),
                )
                mutations.internFqNamesInTransaction(conn, mutations.fqNamesFor(fileUpdate))
                mutations.insertFileDataInTransaction(conn, fileUpdate)
            }

            "remove_file" -> mutations.deleteFileRowsInTransaction(conn, update.prefixId, update.filename)
            "upsert_ref" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingReferencePayload.serializer(),
                    requireNotNull(update.payload)
                )
                val targetPath = payload.targetPath?.let(::normalizePendingPayloadPath)
                    ?.takeIf(SourceIndexFilePolicy::isEligible)
                mutations.internPathsInTransaction(conn, listOfNotNull(path, targetPath))
                mutations.internFqNamesInTransaction(conn, setOf(payload.targetFqName))
                referenceStore.upsertSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePath = path,
                    sourceOffset = payload.sourceOffset,
                    sourceFqName = payload.sourceFqName,
                    targetFqName = payload.targetFqName,
                    targetPath = targetPath,
                    targetOffset = targetPath?.let { payload.targetOffset },
                    edgeKind = payload.edgeKind,
                )
            }

            "remove_ref" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingRemoveReferencePayload.serializer(),
                    requireNotNull(update.payload)
                )
                removeSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePrefixId = update.prefixId,
                    sourceFilename = update.filename,
                    sourceOffset = payload.sourceOffset,
                    targetFqName = payload.targetFqName,
                )
            }

            else -> error("Unsupported pending update operation: ${update.op}")
        }
    }

    private fun removeSymbolReferenceInTransaction(
        conn: Connection,
        sourcePrefixId: Int,
        sourceFilename: String,
        sourceOffset: Int,
        targetFqName: String,
    ) {
        val targetFqId = fqCodec.idFor(targetFqName) ?: return
        conn.prepareStatement(
            """DELETE FROM symbol_references
               WHERE src_prefix_id = ?
                 AND src_filename = ?
                 AND source_offset = ?
                 AND target_fq_id = ?""",
        ).use { stmt ->
            stmt.setInt(1, sourcePrefixId)
            stmt.setString(2, sourceFilename)
            stmt.setInt(3, sourceOffset)
            stmt.setInt(4, targetFqId)
            stmt.executeUpdate()
        }
    }

    private fun normalizePendingPayloadPath(path: String): String {
        val rawPath = Path.of(path)
        return if (rawPath.isAbsolute) {
            rawPath.normalize().toString()
        } else {
            workspaceRoot.resolve(rawPath).normalize().toString()
        }
    }

    private fun markPendingUpdatesApplied(
        conn: Connection,
        updates: List<PendingUpdateRow>,
    ) {
        if (updates.isEmpty()) return
        conn.prepareStatement(
            """UPDATE pending_updates
               SET applied = 1
               WHERE applied = 0 AND prefix_id = ? AND filename = ?""",
        ).use { stmt ->
            updates.forEach { update ->
                stmt.setInt(1, update.prefixId)
                stmt.setString(2, update.filename)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun cleanupAppliedPendingUpdates(conn: Connection) {
        val retentionStartMs = System.currentTimeMillis() - SqliteSourceIndexStoreState.PENDING_UPDATE_RETENTION_MS
        conn.prepareStatement("DELETE FROM pending_updates WHERE applied = 1 AND epoch_ms < ?").use { stmt ->
            stmt.setLong(1, retentionStartMs)
            stmt.executeUpdate()
        }
    }

    private data class PendingUpdateRow(
        val seq: Long,
        val op: String,
        val prefixId: Int,
        val filename: String,
        val payload: String?,
    )

    @Serializable
    private data class PendingFilePayload(
        val identifiers: List<String> = emptyList(),
        val packageName: String? = null,
        val modulePath: String? = null,
        val sourceSet: String? = null,
        val imports: List<String> = emptyList(),
        val wildcardImports: List<String> = emptyList(),
    )

    @Serializable
    private data class PendingReferencePayload(
        val sourceOffset: Int,
        val sourceFqName: String? = null,
        val targetFqName: String,
        val targetPath: String? = null,
        val targetOffset: Int? = null,
        val edgeKind: EdgeKind = EdgeKind.UNKNOWN,
    )

    @Serializable
    private data class PendingRemoveReferencePayload(
        val sourceOffset: Int,
        val targetFqName: String,
    )

}
