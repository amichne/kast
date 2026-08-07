package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.*
import java.sql.Connection

internal enum class FileFqTable(
    private val persistedName: String,
    val readTable: SourceIndexReadTable,
) {
    IMPORTS("file_imports", SourceIndexReadTable.FILE_IMPORTS),
    WILDCARD_IMPORTS("file_wildcard_imports", SourceIndexReadTable.FILE_WILDCARD_IMPORTS),
    ;

    /** Raw extraction is confined to SQLite mutation statements. */
    override fun toString(): String = persistedName
}

private enum class FileOwnedWriteTable(private val persistedName: String) {
    DECLARATIONS("declarations"),
    IDENTIFIER_PATHS("identifier_paths"),
    GRADLE_SOURCE_SETS("file_gradle_source_sets"),
    GRADLE_PROJECTS("file_gradle_projects"),
    METADATA("file_metadata"),
    IMPORTS("file_imports"),
    WILDCARD_IMPORTS("file_wildcard_imports"),
    MANIFEST("file_manifest"),
    ;

    /** Raw extraction is confined to SQLite mutation statements. */
    override fun toString(): String = persistedName
}

internal class SourceIndexFileMutations(
    private val state: SqliteSourceIndexStoreState,
) {
    private val workspaceRoot get() = state.workspaceRoot
    private val pathCodec get() = state.pathCodec
    private val fqCodec get() = state.fqCodec
    internal fun insertFileDataInTransaction(
        conn: Connection,
        update: FileIndexUpdate,
    ) {
        val (prefixId, filename) = pathCodec.encode(update.path)
        conn.prepareStatement("DELETE FROM identifier_paths WHERE prefix_id = ? AND filename = ?").use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM file_metadata WHERE prefix_id = ? AND filename = ?").use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
        for (table in FileFqTable.entries) {
            conn.prepareStatement("DELETE FROM $table WHERE prefix_id = ? AND filename = ?").use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.executeUpdate()
            }
        }
        if (update.identifiers.isNotEmpty()) {
            conn.prepareStatement("INSERT OR IGNORE INTO identifier_paths (identifier, prefix_id, filename) VALUES (?, ?, ?)")
                .use { stmt ->
                for (identifier in update.identifiers) {
                    stmt.setString(1, identifier)
                    stmt.setInt(2, prefixId)
                    stmt.setString(3, filename)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        val packageFqName = packageFqName(update)
        packageFqName?.let { fqCodec.getOrCreate(conn, it) }
        fqCodec.batchEnsure(conn, update.imports + update.wildcardImports)
        val packageState: String
        val packageUnprovenReason: String?
        when (val packageEvidence = update.packageEvidence) {
            IndexedPackageEvidence.ProvenRoot -> {
                packageState = "PROVEN_ROOT"
                packageUnprovenReason = null
            }

            is IndexedPackageEvidence.ProvenNamed -> {
                packageState = "PROVEN_NAMED"
                packageUnprovenReason = null
            }

            is IndexedPackageEvidence.Unproven -> {
                packageState = "UNPROVEN"
                packageUnprovenReason = packageEvidence.reason.name
            }
        }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO file_metadata
               (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            packageFqName
                ?.let(fqCodec::idFor)
                ?.let { stmt.setInt(3, it) }
            ?: stmt.setNull(3, java.sql.Types.INTEGER)
            stmt.setString(4, packageState)
            stmt.setString(5, packageUnprovenReason)
            stmt.setString(6, update.modulePath)
            stmt.setString(7, update.sourceSet)

            stmt.executeUpdate()
        }
        insertGradleProjectsInTransaction(conn, prefixId, filename, update.gradleProjects)
        insertGradleSourceSetsInTransaction(conn, prefixId, filename, update.gradleSourceSets)
        insertFileFqNamesInTransaction(conn, FileFqTable.IMPORTS, prefixId, filename, update.imports)
        insertFileFqNamesInTransaction(
            conn,
            table = FileFqTable.WILDCARD_IMPORTS,
            prefixId,
            filename,
            update.wildcardImports
        )
    }

    internal fun insertManifestInTransaction(
        conn: Connection,
        entries: Map<String, Long>,
    ) {
        if (entries.isEmpty()) return
        conn.prepareStatement("INSERT INTO file_manifest (prefix_id, filename, last_modified_millis) VALUES (?, ?, ?)")
            .use { stmt ->
            entries.forEach { (path, millis) ->
                val (prefixId, filename) = pathCodec.encode(path)
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.setLong(3, millis)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    internal fun pruneReferencesOutsideManifestInTransaction(
        conn: Connection,
        manifestPaths: Set<String>,
    ) {
        if (manifestPaths.isEmpty()) {
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM symbol_references")
                stmt.execute("DELETE FROM declarations")
            }
            return
        }
        conn.createStatement().use { stmt ->
            stmt.execute(
                """DELETE FROM symbol_references
                   WHERE NOT EXISTS (
                       SELECT 1
                       FROM file_manifest manifest
                       WHERE manifest.prefix_id = symbol_references.src_prefix_id
                         AND manifest.filename = symbol_references.src_filename
                   )
                      OR (
                          tgt_prefix_id IS NOT NULL
                          AND NOT EXISTS (
                              SELECT 1
                              FROM file_manifest manifest
                              WHERE manifest.prefix_id = symbol_references.tgt_prefix_id
                                AND manifest.filename = symbol_references.tgt_filename
                          )
                      )""",
            )
            stmt.execute(
                """DELETE FROM declarations
                   WHERE NOT EXISTS (
                       SELECT 1
                       FROM file_manifest manifest
                       WHERE manifest.prefix_id = declarations.prefix_id
                         AND manifest.filename = declarations.filename
                   )""",
            )
        }
    }

    internal fun internPathsInTransaction(
        conn: Connection,
        paths: Iterable<String>,
    ) {
        val dirs = paths.map { pathCodec.decompose(it).first }.toSet()
        pathCodec.batchIntern(conn, dirs)
    }

    internal fun internFqNamesInTransaction(
        conn: Connection,
        fqNames: Set<String>,
    ) {
        fqCodec.batchEnsure(conn, fqNames)
    }

    internal fun fqNamesFor(update: FileIndexUpdate): Set<String> = buildSet {
        packageFqName(update)?.let(::add)
        addAll(update.imports)
        addAll(update.wildcardImports)
    }

    internal fun packageFqName(update: FileIndexUpdate): String? =
        (update.packageEvidence as? IndexedPackageEvidence.ProvenNamed)?.canonicalName?.value

    private fun insertGradleProjectsInTransaction(
        conn: Connection,
        prefixId: Int,
        filename: String,
        projects: Set<BuildQualifiedGradleProjectIdentity>,
    ) {
        if (projects.isEmpty()) return
        conn.prepareStatement(
            """INSERT INTO file_gradle_projects
               (prefix_id, filename, build_root, project_path)
               VALUES (?, ?, ?, ?)""",
        ).use { stmt ->
            projects
                .sortedWith(compareBy({ it.buildRoot.value }, { it.projectPath.value }))
                .forEach { project ->
                    stmt.setInt(1, prefixId)
                    stmt.setString(2, filename)
                    stmt.setString(3, project.buildRoot.value)
                    stmt.setString(4, project.projectPath.value)
                    stmt.addBatch()
                }
            stmt.executeBatch()
        }
    }

    private fun insertGradleSourceSetsInTransaction(
        conn: Connection,
        prefixId: Int,
        filename: String,
        sourceSets: Set<BuildQualifiedGradleSourceSetIdentity>,
    ) {
        if (sourceSets.isEmpty()) return
        conn.prepareStatement(
            """INSERT INTO file_gradle_source_sets
               (prefix_id, filename, build_root, project_path, source_set_name)
               VALUES (?, ?, ?, ?, ?)""",
        ).use { stmt ->
            sourceSets
                .sortedWith(
                    compareBy(
                        { it.project.buildRoot.value },
                        { it.project.projectPath.value },
                        { it.sourceSet.value },
                    ),
                ).forEach { sourceSet ->
                    stmt.setInt(1, prefixId)
                    stmt.setString(2, filename)
                    stmt.setString(3, sourceSet.project.buildRoot.value)
                    stmt.setString(4, sourceSet.project.projectPath.value)
                    stmt.setString(5, sourceSet.sourceSet.value)
                    stmt.addBatch()
                }
            stmt.executeBatch()
        }
    }

    internal fun decodePackageEvidence(rs: java.sql.ResultSet): IndexedPackageEvidence {
        val state = checkNotNull(rs.getString("package_state")) { "Package provenance state is missing" }
        val reason = rs.getString("package_unproven_reason")
        val packageFqId = rs.getNullableInt(rs.findColumn("package_fq_id"))
        val packageName = rs.getString("fq_name")
        return when (state) {
            "PROVEN_ROOT" -> {
                check(packageFqId == null && packageName == null && reason == null) {
                    "Root package provenance contains named or unproven evidence"
                }
                IndexedPackageEvidence.ProvenRoot
            }

            "PROVEN_NAMED" -> {
                check(packageFqId != null && packageName != null && reason == null) {
                    "Named package provenance contains a dangling or inconsistent package reference"
                }
                IndexedPackageEvidence.ProvenNamed(IndexedPackageEvidence.CanonicalName.parse(packageName))
            }

            "UNPROVEN" -> {
                check(packageFqId == null && packageName == null && reason != null) {
                    "Unproven package provenance contains named or missing reason evidence"
                }
                IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.valueOf(reason))
            }

            else -> error("Unknown package provenance state: $state")
        }
    }

    internal fun loadFileFqNames(
        conn: Connection,
        table: FileFqTable,
        target: MutableMap<WorkspaceSourcePath, List<String>>,
    ) {
        val byPath = mutableMapOf<WorkspaceSourcePath, MutableList<String>>()
        val sourceTable = state.readTable(table.readTable)
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT prefix_id, filename, fq_id FROM $sourceTable")
            while (rs.next()) {
                val path = state.requireWorkspaceSourcePath(pathCodec.decode(rs.getInt(1), rs.getString(2)))
                val fqName = fqCodec.resolve(rs.getInt(3))
                byPath.getOrPut(path) { mutableListOf() }.add(fqName)
            }
        }
        byPath.forEach { (path, fqNames) ->
            target[path] = fqNames.sorted()
        }
    }

    private fun insertFileFqNamesInTransaction(
        conn: Connection,
        table: FileFqTable,
        prefixId: Int,
        filename: String,
        fqNames: Set<String>,
    ) {
        if (fqNames.isEmpty()) return
        fqCodec.batchEnsure(conn, fqNames)
        conn.prepareStatement("INSERT OR IGNORE INTO $table (prefix_id, filename, fq_id) VALUES (?, ?, ?)")
            .use { stmt ->
                fqNames.sorted().forEach { fqName ->
                    stmt.setInt(1, prefixId)
                    stmt.setString(2, filename)
                    stmt.setInt(3, checkNotNull(fqCodec.idFor(fqName)) { "FQ name was not interned: $fqName" })
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
    }

    internal fun deleteFileRowsInTransaction(
        conn: Connection,
        prefixId: Int,
        filename: String,
    ) {
        deleteDeclarationSupertypesInTransaction(conn, prefixId, filename)
        for (table in FileOwnedWriteTable.entries) {
            conn.prepareStatement("DELETE FROM $table WHERE prefix_id = ? AND filename = ?").use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.executeUpdate()
            }
        }
        conn.prepareStatement(
            """DELETE FROM symbol_references
               WHERE (src_prefix_id = ? AND src_filename = ?)
                  OR (tgt_prefix_id = ? AND tgt_filename = ?)""",
        ).use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.setInt(3, prefixId)
            stmt.setString(4, filename)
            stmt.executeUpdate()
        }
    }

    internal fun deleteFileContentInTransaction(
        conn: Connection,
        prefixId: Int,
        filename: String,
    ) {
        deleteDeclarationSupertypesInTransaction(conn, prefixId, filename)
        for (table in FileOwnedWriteTable.entries.filterNot { it == FileOwnedWriteTable.MANIFEST }) {
            conn.prepareStatement("DELETE FROM $table WHERE prefix_id = ? AND filename = ?").use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                stmt.executeUpdate()
            }
        }
        conn.prepareStatement(
            "DELETE FROM symbol_references WHERE src_prefix_id = ? AND src_filename = ?",
        ).use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
    }

    private fun deleteDeclarationSupertypesInTransaction(
        conn: Connection,
        prefixId: Int,
        filename: String,
    ) {
        conn.prepareStatement(
            """DELETE FROM declaration_supertypes
               WHERE declaration_fq_id IN (
                   SELECT fq_id FROM declarations WHERE prefix_id = ? AND filename = ?
               )""",
        ).use { stmt ->
            stmt.setInt(1, prefixId)
            stmt.setString(2, filename)
            stmt.executeUpdate()
        }
    }
}
