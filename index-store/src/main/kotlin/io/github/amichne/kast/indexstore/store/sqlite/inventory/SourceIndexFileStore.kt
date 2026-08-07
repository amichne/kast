package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.*
import io.github.amichne.kast.indexstore.store.codec.SourceIndexReadPathResolution
import java.nio.file.Path

internal class SourceIndexFileStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
) {
    private val pathCodec get() = state.pathCodec
    private val fqCodec get() = state.fqCodec
    fun saveFullIndex(
        updates: List<FileIndexUpdate>,
        manifest: Map<String, Long>,
    ) {
        val eligibleUpdates = updates
            .mapNotNull(::parseUpdate)
            .associateBy(ParsedFileIndexUpdate::path)
            .values
        val eligibleManifest = buildMap {
            manifest.forEach { (rawPath, lastModifiedMillis) ->
                state.sourceFilePolicy.sourcePath(Path.of(rawPath))
                    ?.let { path -> put(path, lastModifiedMillis) }
            }
        }
        state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { conn ->
            mutations.internPathsInTransaction(
                conn,
                eligibleUpdates.map { it.path.toDatabasePath() } +
                    eligibleManifest.keys.map(WorkspaceSourcePath::toDatabasePath),
            )
            mutations.internFqNamesInTransaction(conn, eligibleUpdates.flatMapTo(mutableSetOf()) { update ->
                buildList {
                    mutations.packageFqName(update.update)?.let(::add)
                    addAll(update.update.imports)
                    addAll(update.update.wildcardImports)
                }
            })
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM file_wildcard_imports")
                stmt.execute("DELETE FROM file_imports")
                stmt.execute("DELETE FROM identifier_paths")
                stmt.execute("DELETE FROM file_gradle_source_sets")
                stmt.execute("DELETE FROM file_gradle_projects")
                stmt.execute("DELETE FROM file_metadata")
                stmt.execute("DELETE FROM file_manifest")
            }
            for (update in eligibleUpdates) {
                mutations.insertFileDataInTransaction(conn, update.toDatabaseUpdate())
            }
            val databaseManifest = eligibleManifest.mapKeys { (path, _) -> path.toDatabasePath() }
            mutations.insertManifestInTransaction(conn, databaseManifest)
            mutations.pruneReferencesOutsideManifestInTransaction(conn, databaseManifest.keys)
            state.removeIneligibleSourceIndexRows(conn)
            conn.createStatement().use { stmt -> stmt.execute("DELETE FROM pending_updates") }
            state.incrementGenerationInTransaction(conn)
        }
    }

    fun saveFileIndex(update: FileIndexUpdate) {
        val parsedUpdate = parseUpdate(update) ?: return
        state.writeTransaction { conn ->
            mutations.internPathsInTransaction(conn, listOf(parsedUpdate.path.toDatabasePath()))
            mutations.internFqNamesInTransaction(conn, mutations.fqNamesFor(update))
            mutations.insertFileDataInTransaction(conn, parsedUpdate.toDatabaseUpdate())
            state.incrementGenerationInTransaction(conn)
        }
    }

    fun removeFile(path: String) {
        val sourcePath = state.sourceFilePolicy.sourcePath(Path.of(path)) ?: return
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val encodedPath = pathCodec.encodeIfInterned(sourcePath.toDatabasePath()) ?: return
            state.writeTransaction(impact = SourceIndexMutationImpact.MANIFEST) { transaction ->
                mutations.deleteFileRowsInTransaction(transaction, encodedPath.first, encodedPath.second)
                state.incrementGenerationInTransaction(transaction)
            }
        }
    }

    fun loadSourceIndexSnapshot(): SourceIndexSnapshot {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val identifierPaths = state.readTable(SourceIndexReadTable.IDENTIFIER_PATHS)
            val fileMetadata = state.readTable(SourceIndexReadTable.FILE_METADATA)
            val candidatePathsByIdentifier = mutableMapOf<String, MutableList<WorkspaceSourcePath>>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT identifier, prefix_id, filename FROM $identifierPaths")
                while (rs.next()) {
                    candidatePathsByIdentifier
                        .getOrPut(rs.getString(1)) { mutableListOf() }
                        .add(decodeWorkspaceSourcePath(rs.getInt(2), rs.getString(3)))
                }
            }

            val moduleByPath = mutableMapOf<WorkspaceSourcePath, SourceIndexModuleIdentity>()
            val packageByPath = mutableMapOf<WorkspaceSourcePath, String>()
            val importsByPath = mutableMapOf<WorkspaceSourcePath, List<String>>()
            val wildcardImportPackagesByPath = mutableMapOf<WorkspaceSourcePath, List<String>>()

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(

                    "SELECT prefix_id, filename, package_fq_id, module_path, source_set FROM $fileMetadata",
                )
                while (rs.next()) {
                    val path = decodeWorkspaceSourcePath(rs.getInt(1), rs.getString(2))
                    rs.getNullableInt(3)?.let { packageByPath[path] = fqCodec.resolve(it) }
                    val modulePath = rs.getString(4)
                    val sourceSet = rs.getString(5)
                    decodeSourceIndexModuleIdentity(modulePath, sourceSet)?.let { module ->
                        moduleByPath[path] = module
                    }

                }
            }

            mutations.loadFileFqNames(conn, FileFqTable.IMPORTS, importsByPath)
            mutations.loadFileFqNames(conn, FileFqTable.WILDCARD_IMPORTS, wildcardImportPackagesByPath)

            return SourceIndexSnapshot(
                candidatePathsByIdentifier = candidatePathsByIdentifier,
                moduleByPath = moduleByPath,
                packageByPath = packageByPath,
                importsByPath = importsByPath,
                wildcardImportPackagesByPath = wildcardImportPackagesByPath,
            )
        }
    }

    fun gradleProjectsForFile(path: String): Set<BuildQualifiedGradleProjectIdentity> {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val encoded = when (val resolution = pathCodec.encodeForRead(path)) {
                is SourceIndexReadPathResolution.Resolved -> resolution.path
                SourceIndexReadPathResolution.PrefixUnavailable -> return emptySet()
            }
            val projects = state.readTable(SourceIndexReadTable.FILE_GRADLE_PROJECTS)
            return conn.prepareStatement(
                """SELECT build_root, project_path
                   FROM $projects
                   WHERE prefix_id = ? AND filename = ?
                   ORDER BY build_root, project_path""",
            ).use { stmt ->
                stmt.setInt(1, encoded.prefixId)
                stmt.setString(2, encoded.filename)
                val rs = stmt.executeQuery()
                buildSet {
                    while (rs.next()) {
                        add(
                            BuildQualifiedGradleProjectIdentity(
                                buildRoot = WorkspaceRelativeGradleBuildRoot.parse(rs.getString("build_root")),
                                projectPath = GradleProjectPath.parse(rs.getString("project_path")),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun gradleSourceSetsForFile(path: String): Set<BuildQualifiedGradleSourceSetIdentity> {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val encoded = when (val resolution = pathCodec.encodeForRead(path)) {
                is SourceIndexReadPathResolution.Resolved -> resolution.path
                SourceIndexReadPathResolution.PrefixUnavailable -> return emptySet()
            }
            val sourceSets = state.readTable(SourceIndexReadTable.FILE_GRADLE_SOURCE_SETS)
            val projects = state.readTable(SourceIndexReadTable.FILE_GRADLE_PROJECTS)
            return conn.prepareStatement(
                """SELECT source_sets.build_root, source_sets.project_path, source_sets.source_set_name,
                          projects.build_root AS owner_build_root
                   FROM $sourceSets source_sets
                   LEFT JOIN $projects projects
                     ON projects.prefix_id = source_sets.prefix_id
                    AND projects.filename = source_sets.filename
                    AND projects.build_root = source_sets.build_root
                    AND projects.project_path = source_sets.project_path
                   WHERE source_sets.prefix_id = ? AND source_sets.filename = ?
                   ORDER BY source_sets.build_root, source_sets.project_path, source_sets.source_set_name""",
            ).use { stmt ->
                stmt.setInt(1, encoded.prefixId)
                stmt.setString(2, encoded.filename)
                val rs = stmt.executeQuery()
                buildSet {
                    while (rs.next()) {
                        check(rs.getString("owner_build_root") != null) {
                            "Gradle source-set provenance has no matching build-qualified project owner"
                        }
                        add(
                            BuildQualifiedGradleSourceSetIdentity(
                                project = BuildQualifiedGradleProjectIdentity(
                                    buildRoot = WorkspaceRelativeGradleBuildRoot.parse(rs.getString("build_root")),
                                    projectPath = GradleProjectPath.parse(rs.getString("project_path")),
                                ),
                                sourceSet = GradleSourceSetName.parse(rs.getString("source_set_name")),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun packageEvidenceForFile(path: String): IndexedPackageEvidence? {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val encoded = when (val resolution = pathCodec.encodeForRead(path)) {
                is SourceIndexReadPathResolution.Resolved -> resolution.path
                SourceIndexReadPathResolution.PrefixUnavailable -> return null
            }
            val metadata = state.readTable(SourceIndexReadTable.FILE_METADATA)
            val fqNames = state.readTable(SourceIndexReadTable.FQ_NAMES)
            return conn.prepareStatement(
                """SELECT metadata.package_state, metadata.package_unproven_reason,
                          metadata.package_fq_id, packages.fq_name
                   FROM $metadata metadata
                   LEFT JOIN $fqNames packages ON packages.fq_id = metadata.package_fq_id
                   WHERE metadata.prefix_id = ? AND metadata.filename = ?""",
            ).use { stmt ->
                stmt.setInt(1, encoded.prefixId)
                stmt.setString(2, encoded.filename)
                val rs = stmt.executeQuery()
                if (!rs.next()) return@use null
                mutations.decodePackageEvidence(rs)
            }
        }
    }

    private fun parseUpdate(update: FileIndexUpdate): ParsedFileIndexUpdate? =
        state.sourceFilePolicy.sourcePath(Path.of(update.path))
            ?.let { path -> ParsedFileIndexUpdate(path, update) }

    private fun decodeWorkspaceSourcePath(
        prefixId: Int,
        filename: String,
    ): WorkspaceSourcePath = state.requireWorkspaceSourcePath(pathCodec.decode(prefixId, filename))

}

private data class ParsedFileIndexUpdate(
    val path: WorkspaceSourcePath,
    val update: FileIndexUpdate,
) {
    fun toDatabaseUpdate(): FileIndexUpdate = update.copy(path = path.toDatabasePath())
}
