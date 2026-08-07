package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.*
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.store.cache.defaultCacheJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.sql.Connection

internal class SourceIndexPendingUpdateStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val referenceStore: SourceIndexReferenceStore,
) {
    private val pathCodec get() = state.pathCodec
    private val fqCodec get() = state.fqCodec
    fun appendPendingUpdate(
        op: String,
        path: String,
        payload: String?,
        sessionId: String? = null,
    ) {
        val operation = parsePendingUpdateOperation(op)
        val sourcePath = requireNotNull(state.sourceFilePolicy.sourcePath(Path.of(path))) {
            "Pending update source path is not an eligible workspace Kotlin source: $path"
        }
        val parsedPayload = parsePendingUpdatePayload(operation, payload)
        synchronized(state.writeLock) {
            val conn = state.connection()
            val (prefixId, filename) = pathCodec.encodeOrCreate(conn, sourcePath.toDatabasePath())
            conn.prepareStatement(
                """INSERT INTO pending_updates (op, prefix_id, filename, payload, session_id, epoch_ms)
                   VALUES (?, ?, ?, ?, ?, ?)""",
            ).use { stmt ->
                stmt.setString(1, encodePendingUpdateOperation(operation))
                stmt.setInt(2, prefixId)
                stmt.setString(3, filename)
                stmt.setString(4, encodePendingUpdatePayload(parsedPayload))
                stmt.setString(5, sessionId)
                stmt.setLong(6, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    fun reconcilePendingUpdates(): Int {
        return state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { conn ->
            state.loadInterningTables(conn)
            val pending = readLatestPendingUpdates(conn)
            for (update in pending) {
                applyPendingUpdate(conn, update)
            }
            markPendingUpdatesApplied(conn, pending)
            cleanupAppliedPendingUpdates(conn)
            if (pending.isNotEmpty()) state.incrementGenerationInTransaction(conn)
            pending.size
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
                    val prefixId = rs.getInt(3)
                    val filename = rs.getString(4)
                    val operation = parsePendingUpdateOperation(rs.getString(2))
                    val sourcePath = state.sourceFilePolicy.sourcePath(
                        Path.of(pathCodec.decode(prefixId, filename)),
                    )
                    add(
                        PendingUpdateRow(
                            seq = rs.getLong(1),
                            prefixId = prefixId,
                            filename = filename,
                            sourcePath = sourcePath,
                            payload = parsePendingUpdatePayload(operation, rs.getString(5)),
                        ),
                    )
                }
            }
        }

    private fun applyPendingUpdate(
        conn: Connection,
        update: PendingUpdateRow,
    ) {
        val sourcePath = update.sourcePath
        if (sourcePath == null) {
            mutations.deleteFileRowsInTransaction(conn, update.prefixId, update.filename)
            return
        }
        when (val payload = update.payload) {
            is PendingUpdatePayload.UpsertFile -> {
                val fileUpdate = FileIndexUpdate(
                    path = sourcePath.toDatabasePath(),
                    identifiers = payload.value.identifiers.toSet(),
                    packageName = payload.value.packageName,
                    modulePath = payload.value.modulePath,
                    sourceSet = payload.value.sourceSet,
                    imports = payload.value.imports.toSet(),
                    wildcardImports = payload.value.wildcardImports.toSet(),
                )
                mutations.internFqNamesInTransaction(conn, mutations.fqNamesFor(fileUpdate))
                mutations.insertFileDataInTransaction(conn, fileUpdate)
            }

            PendingUpdatePayload.RemoveFile ->
                mutations.deleteFileRowsInTransaction(conn, update.prefixId, update.filename)

            is PendingUpdatePayload.UpsertReference -> {
                mutations.internPathsInTransaction(
                    conn,
                    listOfNotNull(sourcePath.toDatabasePath(), payload.targetPath?.toDatabasePath()),
                )
                mutations.internFqNamesInTransaction(conn, setOf(payload.targetFqName))
                referenceStore.upsertSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePath = sourcePath,
                    sourceOffset = payload.sourceOffset,
                    sourceFqName = payload.sourceFqName,
                    targetFqName = payload.targetFqName,
                    targetPath = payload.targetPath,
                    targetOffset = payload.targetPath?.let { payload.targetOffset },
                    edgeKind = payload.edgeKind,
                )
            }

            is PendingUpdatePayload.RemoveReference -> {
                removeSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePrefixId = update.prefixId,
                    sourceFilename = update.filename,
                    sourceOffset = payload.sourceOffset,
                    targetFqName = payload.targetFqName,
                )
            }
        }
    }

    private fun parsePendingUpdateOperation(raw: String): PendingUpdateOperation =
        defaultCacheJson.decodeFromJsonElement(PendingUpdateOperation.serializer(), JsonPrimitive(raw))

    private fun encodePendingUpdateOperation(operation: PendingUpdateOperation): String =
        defaultCacheJson.encodeToJsonElement(PendingUpdateOperation.serializer(), operation).jsonPrimitive.content

    private fun parsePendingUpdatePayload(
        operation: PendingUpdateOperation,
        rawPayload: String?,
    ): PendingUpdatePayload = when (operation) {
        PendingUpdateOperation.UPSERT_FILE -> PendingUpdatePayload.UpsertFile(
            defaultCacheJson.decodeFromString(
                PendingFilePayload.serializer(),
                requireNotNull(rawPayload) { "upsert_file requires a payload" },
            ),
        )

        PendingUpdateOperation.REMOVE_FILE -> {
            require(rawPayload == null) { "remove_file does not accept a payload" }
            PendingUpdatePayload.RemoveFile
        }

        PendingUpdateOperation.UPSERT_REFERENCE -> {
            val payload = defaultCacheJson.decodeFromString(
                PendingReferencePayload.serializer(),
                requireNotNull(rawPayload) { "upsert_ref requires a payload" },
            )
            PendingUpdatePayload.UpsertReference(
                sourceOffset = payload.sourceOffset,
                sourceFqName = payload.sourceFqName,
                targetFqName = payload.targetFqName,
                targetPath = payload.targetPath?.let { path ->
                    state.sourceFilePolicy.sourcePath(Path.of(path))
                },
                targetOffset = payload.targetOffset,
                edgeKind = payload.edgeKind,
            )
        }

        PendingUpdateOperation.REMOVE_REFERENCE -> {
            val payload = defaultCacheJson.decodeFromString(
                PendingRemoveReferencePayload.serializer(),
                requireNotNull(rawPayload) { "remove_ref requires a payload" },
            )
            PendingUpdatePayload.RemoveReference(
                sourceOffset = payload.sourceOffset,
                targetFqName = payload.targetFqName,
            )
        }
    }

    private fun encodePendingUpdatePayload(payload: PendingUpdatePayload): String? = when (payload) {
        is PendingUpdatePayload.UpsertFile ->
            defaultCacheJson.encodeToString(PendingFilePayload.serializer(), payload.value)

        PendingUpdatePayload.RemoveFile -> null
        is PendingUpdatePayload.UpsertReference -> defaultCacheJson.encodeToString(
            PendingReferencePayload.serializer(),
            PendingReferencePayload(
                sourceOffset = payload.sourceOffset,
                sourceFqName = payload.sourceFqName,
                targetFqName = payload.targetFqName,
                targetPath = payload.targetPath?.toDatabasePath(),
                targetOffset = payload.targetPath?.let { payload.targetOffset },
                edgeKind = payload.edgeKind,
            ),
        )

        is PendingUpdatePayload.RemoveReference -> defaultCacheJson.encodeToString(
            PendingRemoveReferencePayload.serializer(),
            PendingRemoveReferencePayload(
                sourceOffset = payload.sourceOffset,
                targetFqName = payload.targetFqName,
            ),
        )
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
        val prefixId: Int,
        val filename: String,
        val sourcePath: WorkspaceSourcePath?,
        val payload: PendingUpdatePayload,
    )

    @Serializable
    private enum class PendingUpdateOperation {
        @SerialName("upsert_file")
        UPSERT_FILE,

        @SerialName("remove_file")
        REMOVE_FILE,

        @SerialName("upsert_ref")
        UPSERT_REFERENCE,

        @SerialName("remove_ref")
        REMOVE_REFERENCE,
    }

    private sealed interface PendingUpdatePayload {
        data class UpsertFile(val value: PendingFilePayload) : PendingUpdatePayload

        data object RemoveFile : PendingUpdatePayload

        data class UpsertReference(
            val sourceOffset: Int,
            val sourceFqName: String?,
            val targetFqName: String,
            val targetPath: WorkspaceSourcePath?,
            val targetOffset: Int?,
            val edgeKind: EdgeKind,
        ) : PendingUpdatePayload

        data class RemoveReference(
            val sourceOffset: Int,
            val targetFqName: String,
        ) : PendingUpdatePayload
    }

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
