package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.indexstore.api.reference.*

internal class SourceIndexReferenceQuery(
    private val state: SqliteSourceIndexStoreState,
) {
    private val pathCodec get() = state.pathCodec
    private val fqCodec get() = state.fqCodec
    private val pageReadObserver get() = state.pageReadObserver
    fun referencesToSymbol(targetFqName: String): List<SymbolReferenceRow> {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val targetFqId = fqCodec.idFor(targetFqName) ?: return emptyList()
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset, edge_kind
                   FROM symbol_references
                   WHERE target_fq_id = ?""",
            ).use { stmt ->
                stmt.setInt(1, targetFqId)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        val rowSourceFqId = rs.getNullableInt(4)
                        val rowTargetFqId = rs.getInt(5)
                        add(
                            SymbolReferenceRow(
                                sourcePath = pathCodec.decode(rs.getInt(1), rs.getString(2)),
                                sourceOffset = rs.getInt(3),
                                sourceFqName = rowSourceFqId?.let(fqCodec::resolve),
                                targetFqName = fqCodec.resolve(rowTargetFqId),
                                targetPath = state.decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
                                targetOffset = rs.getNullableInt(8),
                                edgeKind = EdgeKind.valueOf(rs.getString(9)),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun generatedReferencePageToSymbol(
        targetFqName: String,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): GeneratedSymbolReferencePage {
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                state.loadInterningTables(conn)
                val generation = state.readGenerationInTransaction(conn)
                pageReadObserver.generationRead()
                val targetFqId = fqCodec.idFor(targetFqName)
                val page = if (targetFqId == null) {
                    SymbolReferencePage(references = emptyList(), nextOffset = null)
                } else {
                    conn.prepareStatement(
                        """SELECT refs.src_prefix_id, refs.src_filename, refs.source_offset,
                                  refs.source_fq_id, refs.target_fq_id, refs.tgt_prefix_id,
                                  refs.tgt_filename, refs.target_offset, refs.edge_kind
                           FROM symbol_references refs
                           JOIN path_prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                           WHERE refs.target_fq_id = ?
                           ORDER BY prefixes.dir_path, refs.src_filename, refs.source_offset
                           LIMIT ? OFFSET ?""",
                    ).use { stmt ->
                        stmt.setInt(1, targetFqId)
                        stmt.setLong(2, maxResults.value.toLong() + 1L)
                        stmt.setInt(3, offset.value)
                        val rs = stmt.executeQuery()
                        val references = buildList {
                            while (size < maxResults.value && rs.next()) {
                                val rowSourceFqId = rs.getNullableInt(4)
                                val rowTargetFqId = rs.getInt(5)
                                add(
                                    SymbolReferenceRow(
                                        sourcePath = pathCodec.decode(rs.getInt(1), rs.getString(2)),
                                        sourceOffset = rs.getInt(3),
                                        sourceFqName = rowSourceFqId?.let(fqCodec::resolve),
                                        targetFqName = fqCodec.resolve(rowTargetFqId),
                                        targetPath = state.decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
                                        targetOffset = rs.getNullableInt(8),
                                        edgeKind = EdgeKind.valueOf(rs.getString(9)),
                                    ),
                                )
                            }
                        }
                        val nextOffset = if (rs.next()) {
                            NonNegativeInt(Math.addExact(offset.value, references.size))
                        } else {
                            null
                        }
                        SymbolReferencePage(references = references, nextOffset = nextOffset)
                    }
                }
                conn.commit()
                return GeneratedSymbolReferencePage(page = page, generation = generation)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun generatedReferencePageToExactSymbol(
        target: ExactReferenceTarget,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): GeneratedSymbolReferencePage {
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                state.loadInterningTables(conn)
                val generation = state.readGenerationInTransaction(conn)
                pageReadObserver.generationRead()
                val targetFqId = fqCodec.idFor(target.fqName)
                val targetPath = pathCodec.encodeIfInterned(target.declarationFile.value)
                val exactIdentityAvailable = targetFqId == null || conn.prepareStatement(
                    """SELECT NOT EXISTS(
                           SELECT 1 FROM symbol_references
                           WHERE target_fq_id = ?
                             AND (tgt_prefix_id IS NULL OR tgt_filename IS NULL OR target_offset IS NULL)
                       )""",
                ).use { stmt ->
                    stmt.setInt(1, targetFqId)
                    stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
                val page = if (targetFqId == null || targetPath == null) {
                    SymbolReferencePage(references = emptyList(), nextOffset = null)
                } else {
                    conn.prepareStatement(
                        """SELECT refs.src_prefix_id, refs.src_filename, refs.source_offset,
                                  refs.source_fq_id, refs.target_fq_id, refs.tgt_prefix_id,
                                  refs.tgt_filename, refs.target_offset, refs.edge_kind
                           FROM symbol_references refs
                           JOIN path_prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                           WHERE refs.target_fq_id = ?
                             AND refs.tgt_prefix_id = ?
                             AND refs.tgt_filename = ?
                             AND refs.target_offset = ?
                           ORDER BY prefixes.dir_path, refs.src_filename, refs.source_offset
                           LIMIT ? OFFSET ?""",
                    ).use { stmt ->
                        stmt.setInt(1, targetFqId)
                        stmt.setInt(2, targetPath.first)
                        stmt.setString(3, targetPath.second)
                        stmt.setInt(4, target.declarationStartOffset.value)
                        stmt.setLong(5, maxResults.value.toLong() + 1L)
                        stmt.setInt(6, offset.value)
                        val rs = stmt.executeQuery()
                        val references = buildList {
                            while (size < maxResults.value && rs.next()) {
                                val rowSourceFqId = rs.getNullableInt(4)
                                val rowTargetFqId = rs.getInt(5)
                                add(
                                    SymbolReferenceRow(
                                        sourcePath = pathCodec.decode(rs.getInt(1), rs.getString(2)),
                                        sourceOffset = rs.getInt(3),
                                        sourceFqName = rowSourceFqId?.let(fqCodec::resolve),
                                        targetFqName = fqCodec.resolve(rowTargetFqId),
                                        targetPath = state.decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
                                        targetOffset = rs.getNullableInt(8),
                                        edgeKind = EdgeKind.valueOf(rs.getString(9)),
                                    ),
                                )
                            }
                        }
                        val nextOffset = if (rs.next()) {
                            NonNegativeInt(Math.addExact(offset.value, references.size))
                        } else {
                            null
                        }
                        SymbolReferencePage(references = references, nextOffset = nextOffset)
                    }
                }
                conn.commit()
                return GeneratedSymbolReferencePage(
                    page = page,
                    generation = generation,
                    exactIdentityAvailable = exactIdentityAvailable,
                )
            } catch (error: Exception) {
                runCatching { conn.rollback() }
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun referencesFromFile(sourcePath: String): List<SymbolReferenceRow> {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val (prefixId, filename) = pathCodec.encodeIfInterned(sourcePath) ?: return emptyList()
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset, edge_kind
                   FROM symbol_references
                   WHERE src_prefix_id = ? AND src_filename = ?""",
            ).use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        val rowSourceFqId = rs.getNullableInt(4)
                        val rowTargetFqId = rs.getInt(5)
                        add(
                            SymbolReferenceRow(
                                sourcePath = pathCodec.decode(rs.getInt(1), rs.getString(2)),
                                sourceOffset = rs.getInt(3),
                                sourceFqName = rowSourceFqId?.let(fqCodec::resolve),
                                targetFqName = fqCodec.resolve(rowTargetFqId),
                                targetPath = state.decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
                                targetOffset = rs.getNullableInt(8),
                                edgeKind = EdgeKind.valueOf(rs.getString(9)),
                            ),
                        )
                    }
                }
            }
        }
    }

}
