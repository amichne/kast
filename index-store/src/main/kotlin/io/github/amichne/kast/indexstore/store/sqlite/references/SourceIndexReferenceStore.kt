package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.reference.*
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
        if (!SourceIndexFilePolicy.isEligible(sourcePath)) {
            fileStore.removeFile(sourcePath)
            return
        }
        val eligibleTargetPath = targetPath?.takeIf(SourceIndexFilePolicy::isEligible)
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                mutations.internPathsInTransaction(conn, listOfNotNull(sourcePath, eligibleTargetPath))
                mutations.internFqNamesInTransaction(conn, listOfNotNull(targetFqName, sourceFqName).toSet())
                upsertSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePath = sourcePath,
                    sourceOffset = sourceOffset,
                    sourceFqName = sourceFqName,
                    targetFqName = targetFqName,
                    targetPath = eligibleTargetPath,
                    targetOffset = eligibleTargetPath?.let { targetOffset },
                    edgeKind = edgeKind,
                )
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

    internal fun upsertSymbolReferenceInTransaction(
        conn: Connection,
        sourcePath: String,
        sourceOffset: Int,
        sourceFqName: String?,
        targetFqName: String,
        targetPath: String?,
        targetOffset: Int?,
        edgeKind: EdgeKind,
    ) {
        val (sourcePrefixId, sourceFilename) = pathCodec.encode(sourcePath)
        val targetPathParts = targetPath?.let { pathCodec.encode(it) }
        val sourceFqId = sourceFqName?.let { fqCodec.getOrCreate(conn, it) }
        val targetFqId = fqCodec.getOrCreate(conn, targetFqName)
        conn.prepareStatement(
            """INSERT OR REPLACE INTO symbol_references
               (src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id, tgt_prefix_id, tgt_filename, target_offset, edge_kind)
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
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                clearReferencesFromFileInTransaction(conn, sourcePath)
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

    internal fun clearReferencesFromFileInTransaction(
        conn: Connection,
        sourcePath: String,
    ) {
        state.loadInterningTables(conn)
        val (prefixId, filename) = pathCodec.encodeIfInterned(sourcePath) ?: return
        conn.prepareStatement("DELETE FROM symbol_references WHERE src_prefix_id = ? AND src_filename = ?")
            .use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
            stmt.executeUpdate()
        }
    }

    fun removeReferencesOutsideSources(sourcePaths: Collection<String>) {
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                if (sourcePaths.isEmpty()) {
                    conn.createStatement().use { stmt -> stmt.execute("DELETE FROM symbol_references") }
                } else {
                    state.loadInterningTables(conn)
                    val encodedSources = sourcePaths.mapNotNull { pathCodec.encodeIfInterned(it) }.toSet()
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
                conn.commit()
            } catch (e: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun replaceReferencesFromFiles(referencesBySource: List<Pair<String, List<SymbolReferenceRow>>>) {
        val eligibleReferencesBySource = referencesBySource
            .filter { (filePath, _) -> SourceIndexFilePolicy.isEligible(filePath) }
            .map { (filePath, refs) ->
                filePath to refs
                    .filter { ref -> SourceIndexFilePolicy.isEligible(ref.sourcePath) }
                    .map { ref ->
                        if (ref.targetPath?.let(SourceIndexFilePolicy::isEligible) != false) {
                            ref
                        } else {
                            ref.copy(targetPath = null, targetOffset = null)
                        }
                    }
            }
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                val pathsToIntern = eligibleReferencesBySource.flatMap { (filePath, refs) ->
                    buildList {
                        add(filePath)
                        refs.forEach { ref ->
                            add(ref.sourcePath)
                            ref.targetPath?.let(::add)
                        }
                    }
                }
                mutations.internPathsInTransaction(conn, pathsToIntern)
                mutations.internFqNamesInTransaction(
                    conn,
                    eligibleReferencesBySource.flatMapTo(mutableSetOf()) { (_, refs) ->
                        refs.flatMap { ref -> listOfNotNull(ref.targetFqName, ref.sourceFqName) }
                    },
                )
                for ((filePath, refs) in eligibleReferencesBySource) {
                    clearReferencesFromFileInTransaction(conn, filePath)
                    refs.forEach { ref ->
                        upsertSymbolReferenceInTransaction(
                            conn = conn,
                            sourcePath = ref.sourcePath,
                            sourceOffset = ref.sourceOffset,
                            sourceFqName = ref.sourceFqName,
                            targetFqName = ref.targetFqName,
                            targetPath = ref.targetPath,
                            targetOffset = ref.targetOffset,
                            edgeKind = ref.edgeKind,
                        )
                    }
                }
                state.removeIneligibleSourceIndexRows(conn)
                state.incrementGenerationInTransaction(conn)
                state.commitManifestMutation(conn)
            } catch (e: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

}
