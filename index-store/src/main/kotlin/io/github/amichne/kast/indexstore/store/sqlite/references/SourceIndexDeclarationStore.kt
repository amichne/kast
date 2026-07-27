package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.reference.*
import java.sql.Connection

internal class SourceIndexDeclarationStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
) {
    private val pathCodec get() = state.pathCodec
    private val fqCodec get() = state.fqCodec
    fun replaceDeclarationsFromFile(
        filePath: String,
        declarations: List<DeclarationRow>,
    ) {
        replaceDeclarationsFromFiles(listOf(filePath to declarations))
    }

    fun replaceDeclarationsFromFiles(declarationsBySource: List<Pair<String, List<DeclarationRow>>>) {
        val eligibleDeclarationsBySource = declarationsBySource
            .filter { (filePath, _) -> SourceIndexFilePolicy.isEligible(filePath) }
            .map { (filePath, declarations) ->
                filePath to declarations.filter { declaration ->
                    declaration.filePath == filePath && SourceIndexFilePolicy.isEligible(declaration.filePath)
                }
            }
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                mutations.internPathsInTransaction(conn, eligibleDeclarationsBySource.map { it.first })
                mutations.internFqNamesInTransaction(
                    conn,
                    eligibleDeclarationsBySource.flatMapTo(mutableSetOf()) { (_, declarations) ->
                        declarations.map { it.fqName }
                    },
                )
                for ((filePath, declarations) in eligibleDeclarationsBySource) {
                    clearDeclarationsFromFileInTransaction(conn, filePath)
                    declarations.forEach { declaration -> insertDeclarationInTransaction(conn, declaration) }
                }
                state.removeIneligibleSourceIndexRows(conn)
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

    fun declarationsWithSupertype(supertypeFqName: String): List<DeclarationRow> {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val supertypeFqId = fqCodec.idFor(supertypeFqName) ?: return emptyList()
            return conn.prepareStatement(
                """SELECT fn_decl.fq_name, d.kind, d.visibility, d.prefix_id, d.filename,
                          d.declaration_offset, d.module_path, d.source_set
                   FROM declarations d
                   JOIN declaration_supertypes ds ON ds.declaration_fq_id = d.fq_id
                   JOIN fq_names fn_decl ON fn_decl.fq_id = d.fq_id
                   WHERE ds.supertype_fq_id = ?""",
            ).use { stmt ->
                stmt.setInt(1, supertypeFqId)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        add(
                            DeclarationRow(
                                fqName = rs.getString(1),
                                kind = DeclarationKind.valueOf(rs.getString(2)),
                                visibility = DeclarationVisibility.valueOf(rs.getString(3)),
                                filePath = pathCodec.decode(rs.getInt(4), rs.getString(5)),
                                declarationOffset = rs.getNullableInt(6),
                                modulePath = rs.getString(7),
                                sourceSet = rs.getString(8),
                            ),
                        )
                    }
                }
            }
        }
    }

    internal fun clearDeclarationsFromFileInTransaction(
        conn: Connection,
        filePath: String,
    ) {
        state.loadInterningTables(conn)
        val (prefixId, filename) = pathCodec.encodeIfInterned(filePath) ?: return
        // Delete supertypes for all declarations in this file first (FK-safe order)
        conn.prepareStatement(
            """DELETE FROM declaration_supertypes WHERE declaration_fq_id IN
               (SELECT fq_id FROM declarations WHERE prefix_id = ? AND filename = ?)""",
        ).use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM declarations WHERE prefix_id = ? AND filename = ?").use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
    }

    internal fun insertDeclarationInTransaction(
        conn: Connection,
        declaration: DeclarationRow,
    ) {
        val (prefixId, filename) = pathCodec.encode(declaration.filePath)
        val fqId = fqCodec.getOrCreate(conn, declaration.fqName)
        conn.prepareStatement(
            """INSERT OR REPLACE INTO declarations
               (fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setInt(1, fqId)
            stmt.setString(2, declaration.kind.name)
            stmt.setString(3, declaration.visibility.name)
            stmt.setInt(4, prefixId)
            stmt.setString(5, filename)
            if (declaration.declarationOffset != null) {
                stmt.setInt(6, declaration.declarationOffset)
            } else {
                stmt.setNull(6, java.sql.Types.INTEGER)
            }
            stmt.setString(7, declaration.modulePath)
            stmt.setString(8, declaration.sourceSet)
            stmt.executeUpdate()
        }
        // Insert supertype edges (re-creating them since declarations uses INSERT OR REPLACE)
        if (declaration.supertypes.isNotEmpty()) {
            conn.prepareStatement(
                "INSERT OR REPLACE INTO declaration_supertypes (declaration_fq_id, supertype_fq_id) VALUES (?, ?)",
            ).use { stmt ->
                for (supertype in declaration.supertypes) {
                    val supertypeFqId = fqCodec.getOrCreate(conn, supertype)
                    stmt.setInt(1, fqId)
                    stmt.setInt(2, supertypeFqId)
                    stmt.executeUpdate()
                }
            }
        }
    }

}
