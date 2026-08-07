package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.api.reference.*
import java.nio.file.Path
import java.sql.Connection

internal class SourceIndexReferenceStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val fileStore: SourceIndexFileStore,
) {
    private val pathCodec get() = state.pathCodec
    private val fqCodec get() = state.fqCodec
    fun upsertSymbolReference(
        sourcePath: String,
        sourceOffset: Int,
        targetFqName: String,
        targetPath: String?,
        targetOffset: Int?,
        sourceFqName: String? = null,
        edgeKind: EdgeKind = EdgeKind.UNKNOWN,
    ) {
        val parsedSourcePath = state.sourceFilePolicy.sourcePath(Path.of(sourcePath))
        if (parsedSourcePath == null) {
            fileStore.removeFile(sourcePath)
            return
        }
        val parsedTargetPath = targetPath?.let { path -> state.sourceFilePolicy.sourcePath(Path.of(path)) }
        state.writeTransaction { conn ->
            mutations.internPathsInTransaction(
                conn,
                listOfNotNull(parsedSourcePath.toDatabasePath(), parsedTargetPath?.toDatabasePath()),
            )
            mutations.internFqNamesInTransaction(conn, listOfNotNull(targetFqName, sourceFqName).toSet())
            upsertSymbolReferenceInTransaction(
                conn = conn,
                sourcePath = parsedSourcePath,
                sourceOffset = sourceOffset,
                sourceFqName = sourceFqName,
                targetFqName = targetFqName,
                targetPath = parsedTargetPath,
                targetOffset = parsedTargetPath?.let { targetOffset },
                edgeKind = edgeKind,
            )
            state.incrementGenerationInTransaction(conn)
        }
    }

    internal fun upsertSymbolReferenceInTransaction(
        conn: Connection,
        sourcePath: WorkspaceSourcePath,
        sourceOffset: Int,
        sourceFqName: String?,
        targetFqName: String,
        targetPath: WorkspaceSourcePath?,
        targetOffset: Int?,
        edgeKind: EdgeKind,
    ) {
        val checkedSourcePath = state.requireWorkspaceSourcePath(sourcePath)
        val checkedTargetPath = targetPath?.let(state::requireWorkspaceSourcePath)
        val (sourcePrefixId, sourceFilename) = pathCodec.encode(checkedSourcePath.toDatabasePath())
        val targetPathParts = checkedTargetPath?.let { path -> pathCodec.encode(path.toDatabasePath()) }
        val sourceFqId = sourceFqName?.let { fqCodec.getOrCreate(conn, it) }
        val targetFqId = fqCodec.getOrCreate(conn, targetFqName)
        conn.prepareStatement(
            """INSERT OR REPLACE INTO symbol_references
               (src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                tgt_prefix_id, tgt_filename, target_offset, edge_kind)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setInt(1, sourcePrefixId)
            stmt.setString(2, sourceFilename)
            stmt.setInt(3, sourceOffset)
            if (sourceFqId != null) stmt.setInt(4, sourceFqId) else stmt.setNull(4, java.sql.Types.INTEGER)
            stmt.setInt(5, targetFqId)
            if (targetPathParts != null) {
                stmt.setInt(6, targetPathParts.first)
                stmt.setString(7, targetPathParts.second)
            } else {
                stmt.setNull(6, java.sql.Types.INTEGER)
                stmt.setNull(7, java.sql.Types.VARCHAR)
            }
            if (targetOffset != null) stmt.setInt(8, targetOffset) else stmt.setNull(8, java.sql.Types.INTEGER)
            stmt.setString(9, edgeKind.name)
            stmt.executeUpdate()
        }
    }

    fun clearReferencesFromFile(sourcePath: String) {
        val parsedSourcePath = state.sourceFilePolicy.sourcePath(Path.of(sourcePath)) ?: return
        state.writeTransaction { conn ->
            clearReferencesFromFileInTransaction(conn, parsedSourcePath)
            state.incrementGenerationInTransaction(conn)
        }
    }

    internal fun clearReferencesFromFileInTransaction(
        conn: Connection,
        sourcePath: WorkspaceSourcePath,
    ) {
        state.loadInterningTables(conn)
        val checkedSourcePath = state.requireWorkspaceSourcePath(sourcePath)
        val (prefixId, filename) = pathCodec.encodeIfInterned(checkedSourcePath.toDatabasePath()) ?: return
        conn.prepareStatement("DELETE FROM symbol_references WHERE src_prefix_id = ? AND src_filename = ?")
            .use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.executeUpdate()
            }
    }

    fun removeReferencesOutsideSources(sourcePaths: Collection<String>) {
        val parsedSourcePaths = sourcePaths.map { sourcePath ->
            requireNotNull(state.sourceFilePolicy.sourcePath(Path.of(sourcePath))) {
                "Retained reference source must be an eligible exact-root Kotlin file: $sourcePath"
            }
        }
        state.writeTransaction { conn ->
            if (parsedSourcePaths.isEmpty()) {
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM symbol_references") }
            } else {
                state.loadInterningTables(conn)
                val encodedSources = parsedSourcePaths
                    .mapNotNull { path -> pathCodec.encodeIfInterned(path.toDatabasePath()) }
                    .toSet()
                if (encodedSources.isEmpty()) {
                    conn.createStatement().use { stmt -> stmt.execute("DELETE FROM symbol_references") }
                } else {
                    conn.createStatement().use { stmt ->
                        stmt.execute(
                            """CREATE TEMP TABLE IF NOT EXISTS temp_valid_sources (
                                prefix_id INTEGER NOT NULL,
                                filename TEXT NOT NULL,
                                PRIMARY KEY (prefix_id, filename)
                            )""",
                        )
                        stmt.execute("DELETE FROM temp_valid_sources")
                    }
                    try {
                        conn.prepareStatement(
                            "INSERT OR IGNORE INTO temp_valid_sources (prefix_id, filename) VALUES (?, ?)",
                        ).use { stmt ->
                            for ((prefixId, filename) in encodedSources) {
                                stmt.setInt(1, prefixId)
                                stmt.setString(2, filename)
                                stmt.addBatch()
                            }
                            stmt.executeBatch()
                        }
                        conn.createStatement().use { stmt ->
                            stmt.execute(
                                """DELETE FROM symbol_references
                                   WHERE NOT EXISTS (
                                       SELECT 1
                                       FROM temp_valid_sources valid
                                       WHERE valid.prefix_id = symbol_references.src_prefix_id
                                         AND valid.filename = symbol_references.src_filename
                                   )""",
                            )
                        }
                    } finally {
                        conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS temp_valid_sources") }
                    }
                }
            }
            state.incrementGenerationInTransaction(conn)
        }
    }

    fun replaceReferencesFromFiles(referencesBySource: List<Pair<String, List<SymbolReferenceRow>>>) {
        val eligibleReferencesBySource = referencesBySource
            .mapNotNull(::parseReferenceBatch)
        state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { conn ->
            val pathsToIntern = eligibleReferencesBySource.flatMap { batch ->
                buildList {
                    add(batch.sourcePath.toDatabasePath())
                    batch.references.forEach { reference ->
                        reference.targetPath?.toDatabasePath()?.let(::add)
                    }
                }
            }
            mutations.internPathsInTransaction(conn, pathsToIntern)
            mutations.internFqNamesInTransaction(
                conn,
                eligibleReferencesBySource.flatMapTo(mutableSetOf()) { batch ->
                    batch.references.flatMap { reference ->
                        listOfNotNull(reference.row.targetFqName, reference.row.sourceFqName)
                    }
                },
            )
            for (batch in eligibleReferencesBySource) {
                clearReferencesFromFileInTransaction(conn, batch.sourcePath)
                batch.references.forEach { reference ->
                    val row = reference.row
                    upsertSymbolReferenceInTransaction(
                        conn = conn,
                        sourcePath = batch.sourcePath,
                        sourceOffset = row.sourceOffset,
                        sourceFqName = row.sourceFqName,
                        targetFqName = row.targetFqName,
                        targetPath = reference.targetPath,
                        targetOffset = reference.targetPath?.let { row.targetOffset },
                        edgeKind = row.edgeKind,
                    )
                }
            }
            state.removeIneligibleSourceIndexRows(conn)
            state.incrementGenerationInTransaction(conn)
        }
    }

    private fun parseReferenceBatch(
        batch: Pair<String, List<SymbolReferenceRow>>,
    ): ParsedReferenceBatch? {
        val sourcePath = state.sourceFilePolicy.sourcePath(Path.of(batch.first)) ?: return null
        val references = batch.second.mapNotNull { row ->
            val rowSourcePath = state.sourceFilePolicy.sourcePath(Path.of(row.sourcePath)) ?: return@mapNotNull null
            if (rowSourcePath != sourcePath) return@mapNotNull null
            ParsedSymbolReference(
                row = row,
                targetPath = row.targetPath?.let { path -> state.sourceFilePolicy.sourcePath(Path.of(path)) },
            )
        }
        return ParsedReferenceBatch(sourcePath, references)
    }

    private data class ParsedReferenceBatch(
        val sourcePath: WorkspaceSourcePath,
        val references: List<ParsedSymbolReference>,
    )

    private data class ParsedSymbolReference(
        val row: SymbolReferenceRow,
        val targetPath: WorkspaceSourcePath?,
    )
}
