package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.indexstore.api.reference.*
import io.github.amichne.kast.indexstore.store.codec.InternedStringReadId
import io.github.amichne.kast.indexstore.store.codec.InternedStringReadIdResolution
import io.github.amichne.kast.indexstore.store.codec.SourceIndexReadPath
import io.github.amichne.kast.indexstore.store.codec.SourceIndexReadPathResolution

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
            val targetFqId = when (val resolution = fqCodec.idForRead(targetFqName)) {
                is InternedStringReadIdResolution.Resolved -> resolution.id
                InternedStringReadIdResolution.Unavailable -> return emptyList()
            }
            val references = state.readTable(SourceIndexReadTable.SYMBOL_REFERENCES)
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset, edge_kind
                   FROM $references
                   WHERE target_fq_id = ?""",
            ).use { stmt ->
                stmt.setInt(1, targetFqId.value)
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
    ): GeneratedSymbolReferencePage = state.writeTransaction { conn ->
        state.loadInterningTables(conn)
        val generation = state.readGenerationInTransaction(conn)
        pageReadObserver.generationRead()
        val targetFqId = fqCodec.idForRead(targetFqName)
        val referenceTable = state.readTable(SourceIndexReadTable.SYMBOL_REFERENCES)
        val prefixes = state.readTable(SourceIndexReadTable.PATH_PREFIXES)
        val page = when (targetFqId) {
            InternedStringReadIdResolution.Unavailable ->
                SymbolReferencePage(references = emptyList(), nextOffset = null)
            is InternedStringReadIdResolution.Resolved -> {
            conn.prepareStatement(
                        """SELECT refs.src_prefix_id, refs.src_filename, refs.source_offset,
                                  refs.source_fq_id, refs.target_fq_id, refs.tgt_prefix_id,
                                  refs.tgt_filename, refs.target_offset, refs.edge_kind
                           FROM $referenceTable refs
                           JOIN $prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                           WHERE refs.target_fq_id = ?
                           ORDER BY prefixes.dir_path, refs.src_filename, refs.source_offset
                           LIMIT ? OFFSET ?""",
            ).use { stmt ->
                stmt.setInt(1, targetFqId.id.value)
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
        }
        GeneratedSymbolReferencePage(page = page, generation = generation)
    }

    fun generatedReferencePageToExactSymbol(
        target: ExactReferenceTarget,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): GeneratedSymbolReferencePage = state.writeTransaction { conn ->
        state.loadInterningTables(conn)
        val generation = state.readGenerationInTransaction(conn)
        pageReadObserver.generationRead()
        val targetFqId = fqCodec.idForRead(target.fqName)
        val targetPath = pathCodec.encodeForRead(target.declarationFile.value)
        val references = state.readTable(SourceIndexReadTable.SYMBOL_REFERENCES)
        val prefixes = state.readTable(SourceIndexReadTable.PATH_PREFIXES)
        val exactIdentityAvailable = when (targetFqId) {
            InternedStringReadIdResolution.Unavailable -> true
            is InternedStringReadIdResolution.Resolved -> conn.prepareStatement(
                    """SELECT NOT EXISTS(
                           SELECT 1 FROM $references
                           WHERE target_fq_id = ?
                             AND (tgt_prefix_id IS NULL OR tgt_filename IS NULL OR target_offset IS NULL)
                       )""",
                ).use { stmt ->
                    stmt.setInt(1, targetFqId.id.value)
                    stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
        }
        val exactAuthority = when {
            targetFqId is InternedStringReadIdResolution.Resolved &&
                targetPath is SourceIndexReadPathResolution.Resolved -> ExactReferenceReadAuthority.Resolved(
                targetFqId.id,
                targetPath.path,
            )
            else -> ExactReferenceReadAuthority.Unavailable
        }
        val page = when (exactAuthority) {
            ExactReferenceReadAuthority.Unavailable ->
                SymbolReferencePage(references = emptyList(), nextOffset = null)
            is ExactReferenceReadAuthority.Resolved -> {
            conn.prepareStatement(
                        """SELECT refs.src_prefix_id, refs.src_filename, refs.source_offset,
                                  refs.source_fq_id, refs.target_fq_id, refs.tgt_prefix_id,
                                  refs.tgt_filename, refs.target_offset, refs.edge_kind
                           FROM $references refs
                           JOIN $prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                           WHERE refs.target_fq_id = ?
                             AND refs.tgt_prefix_id = ?
                             AND refs.tgt_filename = ?
                             AND refs.target_offset = ?
                           ORDER BY prefixes.dir_path, refs.src_filename, refs.source_offset
                           LIMIT ? OFFSET ?""",
            ).use { stmt ->
                stmt.setInt(1, exactAuthority.fqId.value)
                stmt.setInt(2, exactAuthority.path.prefixId)
                stmt.setString(3, exactAuthority.path.filename)
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
        }
        GeneratedSymbolReferencePage(
            page = page,
            generation = generation,
            exactIdentityAvailable = exactIdentityAvailable,
        )
    }

    fun referencesFromFile(sourcePath: String): List<SymbolReferenceRow> {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val encoded = when (val resolution = pathCodec.encodeForRead(sourcePath)) {
                is SourceIndexReadPathResolution.Resolved -> resolution.path
                SourceIndexReadPathResolution.PrefixUnavailable -> return emptyList()
            }
            val references = state.readTable(SourceIndexReadTable.SYMBOL_REFERENCES)
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset, edge_kind
                   FROM $references
                   WHERE src_prefix_id = ? AND src_filename = ?""",
            ).use { stmt ->
                stmt.setInt(1, encoded.prefixId)
                stmt.setString(2, encoded.filename)
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

private sealed interface ExactReferenceReadAuthority {
    data object Unavailable : ExactReferenceReadAuthority

    data class Resolved(
        val fqId: InternedStringReadId,
        val path: SourceIndexReadPath,
    ) : ExactReferenceReadAuthority
}
