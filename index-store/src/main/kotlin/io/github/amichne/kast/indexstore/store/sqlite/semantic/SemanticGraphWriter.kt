package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.indexstore.api.graph.*
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

internal class SemanticGraphWriter(
    private val state: SqliteSourceIndexStoreState,
) {
    private val repositoryBasePath get() = state.repositoryBasePath

    fun replaceSemanticGraphFiles(
        updates: List<SemanticGraphFileIndexUpdate>,
        removedPaths: List<SemanticGraphSourcePath> = emptyList(),
    ): SemanticGraphWriteResult =
        when (val result = replaceSemanticGraphFiles(updates, removedPaths, expectedGeneration = null) {}) {
            is SemanticGraphCommitResult.Committed -> result.writeResult
            is SemanticGraphCommitResult.GenerationChanged ->
                error("An unconditional semantic graph commit cannot reject its generation")
        }

    fun replaceSemanticGraphFilesIfGeneration(
        expectedGeneration: SourceIndexGeneration,
        updates: List<SemanticGraphFileIndexUpdate>,
        removedPaths: List<SemanticGraphSourcePath> = emptyList(),
        commitStageState: (Connection) -> Unit = {},
    ): SemanticGraphCommitResult =
        replaceSemanticGraphFiles(updates, removedPaths, expectedGeneration, commitStageState)

    private fun replaceSemanticGraphFiles(
        updates: List<SemanticGraphFileIndexUpdate>,
        removedPaths: List<SemanticGraphSourcePath>,
        expectedGeneration: SourceIndexGeneration?,
        commitStageState: (Connection) -> Unit,
    ): SemanticGraphCommitResult {
        require(updates.isNotEmpty() || removedPaths.isNotEmpty()) {
            "Semantic graph replacement requires an updated or removed file"
        }
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            return try {
                val actualGeneration = state.readGenerationInTransaction(conn)
                if (expectedGeneration != null && expectedGeneration != actualGeneration) {
                    conn.rollback()
                    return SemanticGraphCommitResult.GenerationChanged(
                        expectedGeneration = expectedGeneration,
                        actualGeneration = actualGeneration,
                    )
                }
                removedPaths
                    .distinct()
                    .sorted()
                    .forEach { path -> deleteSemanticGraphFile(conn, path.value) }
                updates.sortedBy(SemanticGraphFileIndexUpdate::path).forEach { update ->
                    prepareSemanticGraphFileUpdate(conn, update)
                }

                updates.asSequence()
                    .flatMap { update -> update.boundarySymbols.asSequence() }
                    .distinctBy { symbol -> symbol.path }
                    .sortedBy(SemanticGraphSymbol::path)
                    .forEach { symbol -> insertBoundarySemanticFile(conn, symbol.path) }
                updates.sortedBy(SemanticGraphFileIndexUpdate::path).forEach { update ->
                    insertSemanticFile(conn, update)
                }
                updates.asSequence()
                    .flatMap { update -> update.types.asSequence() }
                    .distinctBy { type -> type.stableKey }
                    .sortedBy { type -> type.stableKey.value }
                    .forEach { type -> insertSemanticType(conn, type) }
                updates.asSequence()
                    .flatMap { update -> update.types.asSequence() }
                    .distinctBy { type -> type.stableKey }
                    .sortedBy { type -> type.stableKey.value }
                    .forEach { type -> replaceSemanticTypeEdges(conn, type) }
                updates.asSequence()
                    .flatMap { update -> update.boundarySymbols.asSequence() }
                    .distinctBy(SemanticGraphSymbol::canonicalKey)
                    .sortedBy(SemanticGraphSymbol::canonicalKey)
                    .forEach { symbol -> insertSemanticSymbol(conn, symbol, authoritative = false) }
                updates.asSequence()
                    .flatMap { update -> update.symbols.asSequence() }
                    .distinctBy(SemanticGraphSymbol::canonicalKey)
                    .sortedBy(SemanticGraphSymbol::canonicalKey)
                    .forEach { symbol -> insertSemanticSymbol(conn, symbol, authoritative = true) }
                updates.asSequence()
                    .flatMap { update -> (update.boundarySymbols + update.symbols).asSequence() }
                    .distinctBy(SemanticGraphSymbol::canonicalKey)
                    .sortedBy(SemanticGraphSymbol::canonicalKey)
                    .forEach { symbol -> updateSemanticSymbolOwner(conn, symbol) }
                updates.sortedBy(SemanticGraphFileIndexUpdate::path).forEach { update ->
                    insertSemanticEdges(conn, update)
                }
                commitStageState(conn)
                state.incrementGenerationInTransaction(conn)
                val generation = state.readGenerationInTransaction(conn)
                conn.commit()
                SemanticGraphCommitResult.Committed(
                    SemanticGraphWriteResult(
                        generation = generation,
                        fileCount = updates.size,
                        symbolCount = updates.sumOf { update -> update.symbols.size },
                        edgeOccurrenceCount = updates.sumOf { update -> update.relations.size },
                    ),
                )
            } catch (failure: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }
    private fun insertBoundarySemanticFile(conn: Connection, path: SemanticGraphSourcePath) {
        conn.prepareStatement(
            """INSERT INTO semantic_files(path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
               VALUES (?, NULL, NULL, NULL, 'CACHED', '[]')
               ON CONFLICT(path) DO NOTHING""",
        ).use { statement ->
            statement.setString(1, path.value)
            statement.executeUpdate()
        }
    }
    private fun prepareSemanticGraphFileUpdate(conn: Connection, update: SemanticGraphFileIndexUpdate) {
        val fileId = optionalSemanticId(
            conn,
            "SELECT id FROM semantic_files WHERE path = ?",
            update.path.value,
        ) ?: return
        conn.prepareStatement("DELETE FROM semantic_edge_occurrences WHERE source_file_id = ?").use { statement ->
            statement.setLong(1, fileId)
            statement.executeUpdate()
        }
        conn.prepareStatement("UPDATE semantic_symbols SET owner_id = NULL WHERE file_id = ?").use { statement ->
            statement.setLong(1, fileId)
            statement.executeUpdate()
        }

        val retainedKeys = update.symbols.mapTo(mutableSetOf()) { symbol -> symbol.canonicalKey.value }
        val removedKeys = conn.prepareStatement(
            "SELECT stable_key FROM semantic_symbols WHERE file_id = ? ORDER BY stable_key",
        ).use { statement ->
            statement.setLong(1, fileId)
            val rows = statement.executeQuery()
            buildList {
                while (rows.next()) {
                    rows.getString(1).takeUnless(retainedKeys::contains)?.let(::add)
                }
            }
        }
        conn.prepareStatement(
            "DELETE FROM semantic_symbols WHERE file_id = ? AND stable_key = ?",
        ).use { statement ->
            removedKeys.forEach { key ->
                statement.setLong(1, fileId)
                statement.setString(2, key)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
    private fun insertSemanticFile(conn: Connection, update: SemanticGraphFileIndexUpdate) {
        clearRepositoryOverlayTombstone(conn, update.path.value)
        conn.prepareStatement(
            """INSERT INTO semantic_files(
                   path, package_name, module_name, content_hash, refresh_status, diagnostics_json
               ) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(path) DO UPDATE SET
                   package_name = excluded.package_name,
                   module_name = excluded.module_name,
                   content_hash = excluded.content_hash,
                   refresh_status = excluded.refresh_status,
                   diagnostics_json = excluded.diagnostics_json""",
        ).use { statement ->
            statement.setString(1, update.path.value)
            statement.setString(2, update.packageName)
            statement.setString(3, update.moduleName)
            statement.setString(4, update.contentHash.value)
            statement.setString(5, update.status.name)
            statement.setString(6, Json.encodeToString(update.diagnostics))
            statement.executeUpdate()
        }
    }
    private fun insertSemanticType(
        conn: Connection,
        type: io.github.amichne.kast.api.contract.result.SemanticGraphTypeFact,
    ) {
        conn.prepareStatement(
            """INSERT INTO semantic_types(stable_key, kind, classifier, nullability, debug_text)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(stable_key) DO UPDATE SET
                   kind = excluded.kind,
                   classifier = excluded.classifier,
                   nullability = excluded.nullability,
                   debug_text = excluded.debug_text""",
        ).use { statement ->
            statement.setString(1, type.stableKey.value)
            statement.setString(2, type.kind.name)
            statement.setString(3, type.classifier?.value)
            statement.setString(4, type.nullability.name)
            statement.setString(5, type.debugText.value)
            statement.executeUpdate()
        }
    }
    private fun replaceSemanticTypeEdges(
        conn: Connection,
        type: io.github.amichne.kast.api.contract.result.SemanticGraphTypeFact,
    ) {
        val parentId = semanticTypeId(conn, type.stableKey.value)
        conn.prepareStatement("DELETE FROM semantic_type_edges WHERE parent_type_id = ?").use { statement ->
            statement.setLong(1, parentId)
            statement.executeUpdate()
        }
        conn.prepareStatement(
            """INSERT INTO semantic_type_edges(parent_type_id, child_type_id, role, position, variance)
               VALUES (?, ?, ?, ?, ?)""",
        ).use { statement ->
            type.edges.sortedWith(compareBy({ edge -> edge.role.name }, { edge -> edge.position.value }))
                .forEach { edge ->
                    statement.setLong(1, parentId)
                    statement.setObject(2, edge.childKey?.value?.let { key -> semanticTypeId(conn, key) })
                    statement.setString(3, edge.role.name)
                    statement.setInt(4, edge.position.value)
                    statement.setString(5, edge.variance.name)
                    statement.addBatch()
                }
            statement.executeBatch()
        }
    }
    private fun insertSemanticSymbol(conn: Connection, symbol: SemanticGraphSymbol, authoritative: Boolean) {
        val sql = buildString {
            append(
                """INSERT INTO semantic_symbols(
                       stable_key, file_id, owner_id, kind, name, fq_name, signature,
                       visibility, modality, origin, is_expect, is_actual, is_override,
                       is_sealed, is_delegated, declared_type_id, receiver_type_id, return_type_id,
                       start_offset, end_offset, line
                   ) VALUES (
                       ?, (SELECT id FROM semantic_files WHERE path = ?), NULL, ?, ?, ?, ?,
                       ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                   )""",
            )
            if (authoritative) {
                append(
                    """ ON CONFLICT(stable_key) DO UPDATE SET
                            file_id = excluded.file_id,
                            kind = excluded.kind,
                            name = excluded.name,
                            fq_name = excluded.fq_name,
                            signature = excluded.signature,
                            visibility = excluded.visibility,
                            modality = excluded.modality,
                            origin = excluded.origin,
                            is_expect = excluded.is_expect,
                            is_actual = excluded.is_actual,
                            is_override = excluded.is_override,
                            is_sealed = excluded.is_sealed,
                            is_delegated = excluded.is_delegated,
                            declared_type_id = excluded.declared_type_id,
                            receiver_type_id = excluded.receiver_type_id,
                            return_type_id = excluded.return_type_id,
                            start_offset = excluded.start_offset,
                            end_offset = excluded.end_offset,
                            line = excluded.line""",
                )
            } else {
                append(" ON CONFLICT(stable_key) DO NOTHING")
            }
        }
        conn.prepareStatement(sql).use { statement ->
            statement.setString(1, symbol.canonicalKey.value)
            statement.setString(2, symbol.path.value)
            statement.setString(3, symbol.kind.name)
            statement.setString(4, symbol.name.value)
            statement.setString(5, symbol.fqName?.value)
            statement.setString(6, symbol.signature?.value)
            statement.setString(7, symbol.visibility.name)
            statement.setString(8, symbol.modality?.name)
            statement.setString(9, symbol.origin.name)
            statement.setInt(10, if (symbol.flags.isExpect) 1 else 0)
            statement.setInt(11, if (symbol.flags.isActual) 1 else 0)
            statement.setInt(12, if (symbol.flags.isOverride) 1 else 0)
            statement.setInt(13, if (symbol.flags.isSealed) 1 else 0)
            statement.setInt(14, if (symbol.flags.isDelegated) 1 else 0)
            statement.setObject(15, symbol.declaredTypeKey?.value?.let { key -> semanticTypeIdOrNull(conn, key) })
            statement.setObject(16, symbol.receiverTypeKey?.value?.let { key -> semanticTypeIdOrNull(conn, key) })
            statement.setObject(17, symbol.returnTypeKey?.value?.let { key -> semanticTypeIdOrNull(conn, key) })
            statement.setInt(18, symbol.startOffset.value)
            statement.setInt(19, symbol.endOffset.value)
            statement.setInt(20, symbol.line.value)
            statement.executeUpdate()
        }
        if (authoritative) {
            val symbolId = semanticSymbolId(conn, symbol.canonicalKey.value)
            conn.prepareStatement("DELETE FROM semantic_symbol_annotations WHERE symbol_id = ?").use { statement ->
                statement.setLong(1, symbolId)
                statement.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO semantic_symbol_annotations(symbol_id, annotation_name) VALUES (?, ?)",
            ).use { statement ->
                symbol.annotations.distinct().sortedBy(NonBlankString::value).forEach { annotation ->
                    statement.setLong(1, symbolId)
                    statement.setString(2, annotation.value)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }
    private fun updateSemanticSymbolOwner(conn: Connection, symbol: SemanticGraphSymbol) {
        val ownerKey = symbol.ownerKey ?: return
        conn.prepareStatement(
            """UPDATE semantic_symbols
               SET owner_id = (SELECT id FROM semantic_symbols WHERE stable_key = ?)
               WHERE stable_key = ?""",
        ).use { statement ->
            statement.setString(1, ownerKey.value)
            statement.setString(2, symbol.canonicalKey.value)
            statement.executeUpdate()
        }
    }
    private fun insertSemanticEdges(conn: Connection, update: SemanticGraphFileIndexUpdate) {
        val sourceFileId = semanticFileId(conn, update.path.value)
        conn.prepareStatement(
            """INSERT INTO semantic_edge_occurrences(
                   source_id, target_id, source_file_id, kind, context, resolved_target_id,
                   start_offset, end_offset, line
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { statement ->
            update.relations.sortedWith(
                compareBy<SemanticGraphRelation>(
                    SemanticGraphRelation::sourceKey,
                    SemanticGraphRelation::targetKey,
                    { relation -> relation.kind.name },
                    { relation -> relation.context.name },
                    SemanticGraphRelation::startOffset,
                ),
            ).forEach { relation ->
                statement.setLong(1, semanticSymbolId(conn, relation.sourceKey.value))
                statement.setLong(2, semanticSymbolId(conn, relation.targetKey.value))
                statement.setLong(3, sourceFileId)
                statement.setString(4, relation.kind.name)
                statement.setString(5, relation.context.name)
                statement.setObject(
                    6,
                    relation.resolvedTargetKey?.value?.let { key -> semanticSymbolIdOrNull(conn, key) },
                )
                statement.setInt(7, relation.startOffset.value)
                statement.setInt(8, relation.endOffset.value)
                statement.setInt(9, relation.line.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
    private fun semanticFileId(conn: Connection, path: String): Long =
        requiredSemanticId(conn, "SELECT id FROM semantic_files WHERE path = ?", path)
    private fun semanticSymbolId(conn: Connection, key: String): Long =
        requiredSemanticId(conn, "SELECT id FROM semantic_symbols WHERE stable_key = ?", key)
    private fun semanticSymbolIdOrNull(conn: Connection, key: String): Long? =
        optionalSemanticId(conn, "SELECT id FROM semantic_symbols WHERE stable_key = ?", key)
    private fun semanticTypeId(conn: Connection, key: String): Long =
        requiredSemanticId(conn, "SELECT id FROM semantic_types WHERE stable_key = ?", key)
    private fun semanticTypeIdOrNull(conn: Connection, key: String): Long? =
        optionalSemanticId(conn, "SELECT id FROM semantic_types WHERE stable_key = ?", key)
    private fun requiredSemanticId(conn: Connection, sql: String, value: String): Long =
        requireNotNull(optionalSemanticId(conn, sql, value)) { "Missing canonical semantic identity: $value" }
    private fun optionalSemanticId(conn: Connection, sql: String, value: String): Long? =
        conn.prepareStatement(sql).use { statement ->
            statement.setString(1, value)
            val rows = statement.executeQuery()
            if (rows.next()) rows.getLong(1) else null
        }
    private fun deleteSemanticGraphFile(conn: Connection, path: String) {
        conn.prepareStatement("DELETE FROM semantic_files WHERE path = ?").use { statement ->
            statement.setString(1, path)
            statement.executeUpdate()
        }
        if (repositoryBasePath != null) {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO repository_overlay_tombstones(path) VALUES (?)",
            ).use { statement ->
                statement.setString(1, path)
                statement.executeUpdate()
            }
        }
    }
    private fun clearRepositoryOverlayTombstone(conn: Connection, path: String) {
        conn.prepareStatement("DELETE FROM repository_overlay_tombstones WHERE path = ?").use { statement ->
            statement.setString(1, path)
            statement.executeUpdate()
        }
    }
}
