package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphIndexSnapshot
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphIndexSummary
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphScopeSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.sql.Connection

internal class SemanticGraphReader(
    private val state: SqliteSourceIndexStoreState,
) {
    fun readSemanticGraph(filePaths: Collection<SemanticGraphSourcePath>): SemanticGraphIndexSnapshot {
        synchronized(state.writeLock) {
            val conn = state.connection()
            val generation = state.readGenerationInTransaction(conn)
            prepareSemanticGraphScope(conn, filePaths)
            val files = readSemanticGraphFiles(conn)
            val symbols = conn.prepareStatement(
                semanticSymbolSelect(
                    """FROM requested_semantic_file_ids requested
                       JOIN semantic_symbols symbols INDEXED BY idx_semantic_symbols_file_id_id
                         ON symbols.file_id = requested.id
                       JOIN semantic_files files ON files.id = symbols.file_id
                       LEFT JOIN semantic_symbols owner ON owner.id = symbols.owner_id""",
                    "ORDER BY symbols.id",
                ),
            ).use { statement ->
                val rows = statement.executeQuery()
                buildList { while (rows.next()) add(readSemanticSymbol(rows)) }
            }
            val boundarySymbols = conn.prepareStatement(
                semanticSymbolSelect(
                    """FROM semantic_symbols symbols
                       JOIN semantic_files files ON files.id = symbols.file_id
                       LEFT JOIN semantic_symbols owner ON owner.id = symbols.owner_id""",
                    """WHERE symbols.id IN (
                           SELECT edges.target_id
                           FROM semantic_edge_occurrences edges INDEXED BY idx_semantic_edges_source_file_id_id
                           WHERE edges.source_file_id IN (SELECT id FROM requested_semantic_file_ids)
                       )
                       AND symbols.file_id NOT IN (SELECT id FROM requested_semantic_file_ids)
                       ORDER BY symbols.id""",
                ),
            ).use { statement ->
                val rows = statement.executeQuery()
                buildList { while (rows.next()) add(readSemanticSymbol(rows)) }
            }
            val relations = conn.prepareStatement(
                """SELECT source.stable_key, target.stable_key, resolved.stable_key,
                          edges.kind, edges.context, files.path,
                          edges.start_offset, edges.end_offset, edges.line
                   FROM semantic_edge_occurrences edges INDEXED BY idx_semantic_edges_source_file_id_id
                   JOIN semantic_symbols source ON source.id = edges.source_id
                   JOIN semantic_symbols target ON target.id = edges.target_id
                   LEFT JOIN semantic_symbols resolved ON resolved.id = edges.resolved_target_id
                   JOIN semantic_files files ON files.id = edges.source_file_id
                   WHERE edges.source_file_id IN (SELECT id FROM requested_semantic_file_ids)
                   ORDER BY edges.id""",
            ).use { statement ->
                val rows = statement.executeQuery()
                buildList {
                    while (rows.next()) {
                        add(
                            SemanticGraphRelation(
                                sourceKey = SemanticGraphSymbolKey.parse(rows.getString(1)),
                                targetKey = SemanticGraphSymbolKey.parse(rows.getString(2)),
                                resolvedTargetKey = rows.getString(3)?.let(SemanticGraphSymbolKey::parse),
                                kind = SemanticGraphRelationKind.valueOf(rows.getString(4)),
                                context = SemanticGraphRelationContext.valueOf(rows.getString(5)),
                                sourcePath = SemanticGraphSourcePath.parse(rows.getString(6)),
                                startOffset = ByteOffset(rows.getInt(7)),
                                endOffset = ByteOffset(rows.getInt(8)),
                                line = LineNumber(rows.getInt(9)),
                            ),
                        )
                    }
                }
            }
            return SemanticGraphIndexSnapshot(generation, files, symbols, boundarySymbols, relations)
        }
    }

    fun readSemanticGraphSummary(filePaths: Collection<SemanticGraphSourcePath>): SemanticGraphIndexSummary =
        synchronized(state.writeLock) {
            val conn = state.connection()
            prepareSemanticGraphScope(conn, filePaths)
            SemanticGraphIndexSummary(
                generation = state.readGenerationInTransaction(conn),
                files = readSemanticGraphFiles(conn),
                symbolCount = countRequestedRows(conn, "semantic_symbols", "file_id"),
                edgeOccurrenceCount = countRequestedRows(conn, "semantic_edge_occurrences", "source_file_id"),
            )
        }

    private fun readSemanticGraphFiles(conn: Connection): List<SemanticGraphFileCoverage> =
        conn.prepareStatement(
            """SELECT files.path, files.content_hash, files.refresh_status, files.diagnostics_json
               FROM requested_semantic_file_ids requested
               JOIN semantic_files files ON files.id = requested.id
               ORDER BY files.path""",
        ).use { statement ->
            val rows = statement.executeQuery()
            buildList {
                while (rows.next()) {
                    add(
                        SemanticGraphFileCoverage(
                            path = SemanticGraphSourcePath.parse(rows.getString(1)),
                            contentHash = rows.getString(2)?.let(SemanticGraphSha256::parse),
                            status = SemanticGraphFileStatus.valueOf(rows.getString(3)),
                            diagnostics = Json.decodeFromString(rows.getString(4)),
                        ),
                    )
                }
            }
        }

    private fun countRequestedRows(conn: Connection, table: String, fileColumn: String): Int =
        conn.prepareStatement(
            "SELECT COUNT(*) FROM $table WHERE $fileColumn IN (SELECT id FROM requested_semantic_file_ids)",
        ).use { statement ->
            val rows = statement.executeQuery()
            check(rows.next())
            rows.getInt(1)
        }

    private fun prepareSemanticGraphScope(conn: Connection, filePaths: Collection<SemanticGraphSourcePath>) {
        conn.createStatement().use { statement ->
            statement.execute(
                "CREATE TEMP TABLE IF NOT EXISTS requested_semantic_file_ids(id INTEGER PRIMARY KEY) WITHOUT ROWID",
            )
            statement.execute("DELETE FROM requested_semantic_file_ids")
        }
        conn.prepareStatement(
            """INSERT OR IGNORE INTO requested_semantic_file_ids(id)
               SELECT id FROM semantic_files WHERE path = ?""",
        ).use { statement ->
            filePaths.distinct().sorted().forEach { path ->
                statement.setString(1, path.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun semanticSymbolSelect(from: String, tail: String): String =
        """SELECT symbols.stable_key, symbols.kind, symbols.name, symbols.fq_name, symbols.signature,
                  owner.stable_key, symbols.visibility, symbols.modality, symbols.origin,
                  symbols.is_expect, symbols.is_actual, symbols.is_override, symbols.is_sealed,
                  symbols.is_delegated, declared.stable_key, receiver.stable_key, returned.stable_key,
                  files.path, symbols.start_offset, symbols.end_offset, symbols.line,
                  COALESCE((
                      SELECT json_group_array(annotation_name)
                      FROM semantic_symbol_annotations annotations
                      WHERE annotations.symbol_id = symbols.id
                  ), '[]')
           $from
           LEFT JOIN semantic_types declared ON declared.id = symbols.declared_type_id
           LEFT JOIN semantic_types receiver ON receiver.id = symbols.receiver_type_id
           LEFT JOIN semantic_types returned ON returned.id = symbols.return_type_id
           $tail"""

    private fun readSemanticSymbol(rows: java.sql.ResultSet): SemanticGraphSymbol =
        SemanticGraphSymbol(
            canonicalKey = SemanticGraphSymbolKey.parse(rows.getString(1)),
            kind = SemanticGraphSymbolKind.valueOf(rows.getString(2)),
            name = NonBlankString(rows.getString(3)),
            fqName = rows.getString(4)?.let(::FqName),
            signature = rows.getString(5)?.let(::NonBlankString),
            ownerKey = rows.getString(6)?.let(SemanticGraphSymbolKey::parse),
            visibility = SemanticGraphVisibility.valueOf(rows.getString(7)),
            modality = rows.getString(8)?.let(SemanticGraphModality::valueOf),
            origin = SemanticGraphOrigin.valueOf(rows.getString(9)),
            flags = SemanticGraphSymbolFlags(
                isExpect = rows.getInt(10) != 0,
                isActual = rows.getInt(11) != 0,
                isOverride = rows.getInt(12) != 0,
                isSealed = rows.getInt(13) != 0,
                isDelegated = rows.getInt(14) != 0,
            ),
            declaredTypeKey = rows.getString(15)?.let(::NonBlankString),
            receiverTypeKey = rows.getString(16)?.let(::NonBlankString),
            returnTypeKey = rows.getString(17)?.let(::NonBlankString),
            path = SemanticGraphSourcePath.parse(rows.getString(18)),
            startOffset = ByteOffset(rows.getInt(19)),
            endOffset = ByteOffset(rows.getInt(20)),
            line = LineNumber(rows.getInt(21)),
            annotations = Json.decodeFromString<List<String>>(rows.getString(22)).map(::NonBlankString),
        )

    fun semanticGraphSymbolKeys(): Set<SemanticGraphSymbolKey> = synchronized(state.writeLock) {
        state.connection().prepareStatement(
            "SELECT stable_key FROM semantic_symbols ORDER BY stable_key",
        ).use { statement ->
            val rows = statement.executeQuery()
            buildSet {
                while (rows.next()) add(SemanticGraphSymbolKey.parse(rows.getString(1)))
            }
        }
    }

    fun semanticGraphSourcePaths(): Set<SemanticGraphSourcePath> =
        semanticGraphScopeSnapshot().sourcePaths

    fun semanticGraphScopeSnapshot(): SemanticGraphScopeSnapshot = synchronized(state.writeLock) {
        val connection = state.connection()
        SemanticGraphScopeSnapshot(
            generation = state.readGenerationInTransaction(connection),
            sourcePaths = readSemanticGraphSourcePaths(connection),
        )
    }

    private fun readSemanticGraphSourcePaths(connection: Connection): Set<SemanticGraphSourcePath> {
        val sql = if (state.repositoryBasePath == null) {
            "SELECT path FROM semantic_files WHERE refresh_status != 'CACHED' ORDER BY path"
        } else {
            """SELECT path FROM semantic_files WHERE refresh_status != 'CACHED'
               UNION
               SELECT base.path
               FROM repository_base.semantic_files base
               WHERE base.refresh_status != 'CACHED'
                 AND NOT EXISTS (
                     SELECT 1 FROM repository_overlay_tombstones tombstones
                     WHERE tombstones.path = base.path
                 )
                 AND NOT EXISTS (
                     SELECT 1 FROM semantic_files overlay
                     WHERE overlay.path = base.path
                 )
               ORDER BY path"""
        }
        return connection.prepareStatement(sql).use { statement ->
            val rows = statement.executeQuery()
            buildSet {
                while (rows.next()) add(SemanticGraphSourcePath.parse(rows.getString(1)))
            }
        }
    }
}
