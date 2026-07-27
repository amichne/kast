package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.*
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
        val eligibleUpdates = updates.filter { update -> SourceIndexFilePolicy.isEligible(update.path) }
        val eligibleManifest = manifest.filterKeys(SourceIndexFilePolicy::isEligible)
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                mutations.internPathsInTransaction(conn, eligibleUpdates.map { it.path } + eligibleManifest.keys)
                mutations.internFqNamesInTransaction(conn, eligibleUpdates.flatMapTo(mutableSetOf()) { update ->
                    buildList {
                        mutations.packageFqName(update)?.let(::add)
                        addAll(update.imports)
                        addAll(update.wildcardImports)
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
                    mutations.insertFileDataInTransaction(conn, update)
                }
                mutations.insertManifestInTransaction(conn, eligibleManifest)
                mutations.pruneReferencesOutsideManifestInTransaction(conn, eligibleManifest.keys)
                state.removeIneligibleSourceIndexRows(conn)
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM pending_updates") }
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

    fun saveFileIndex(update: FileIndexUpdate) {
        if (!SourceIndexFilePolicy.isEligible(update.path)) {
            removeFile(update.path)
            return
        }
        synchronized(state.writeLock) {
            val conn = state.connection()
            conn.autoCommit = false
            try {
                mutations.internPathsInTransaction(conn, listOf(update.path))
                mutations.internFqNamesInTransaction(conn, mutations.fqNamesFor(update))
                mutations.insertFileDataInTransaction(conn, update)
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

    fun removeFile(path: String) {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val encodedPath = pathCodec.encodeIfInterned(path) ?: return
            conn.autoCommit = false
            try {
                mutations.deleteFileRowsInTransaction(conn, encodedPath.first, encodedPath.second)
                state.incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun loadSourceIndexSnapshot(): SourceIndexSnapshot {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            val candidatePathsByIdentifier = mutableMapOf<String, MutableList<String>>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT identifier, prefix_id, filename FROM identifier_paths")
                while (rs.next()) {
                    candidatePathsByIdentifier
                        .getOrPut(rs.getString(1)) { mutableListOf() }
                        .add(pathCodec.decode(rs.getInt(2), rs.getString(3)))
                }
            }

            val moduleNameByPath = mutableMapOf<String, String>()
            val packageByPath = mutableMapOf<String, String>()
            val importsByPath = mutableMapOf<String, List<String>>()
            val wildcardImportPackagesByPath = mutableMapOf<String, List<String>>()

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(

                    "SELECT prefix_id, filename, package_fq_id, module_path, source_set FROM file_metadata",
                )
                while (rs.next()) {
                    val path = pathCodec.decode(rs.getInt(1), rs.getString(2))
                    rs.getNullableInt(3)?.let { packageByPath[path] = fqCodec.resolve(it) }
                    val modulePath = rs.getString(4)
                    val sourceSet = rs.getString(5)
                    if (modulePath != null) {
                        val reconstructed = if (sourceSet != null) "$modulePath[$sourceSet]" else modulePath
                        moduleNameByPath[path] = reconstructed
                    }

                }
            }

            mutations.loadFileFqNames(conn, "file_imports", importsByPath)
            mutations.loadFileFqNames(conn, "file_wildcard_imports", wildcardImportPackagesByPath)

            return SourceIndexSnapshot(
                candidatePathsByIdentifier = candidatePathsByIdentifier,
                moduleNameByPath = moduleNameByPath,
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
            val (prefixId, filename) = pathCodec.encodeIfInterned(path) ?: return emptySet()
            return conn.prepareStatement(
                """SELECT build_root, project_path
                   FROM file_gradle_projects
                   WHERE prefix_id = ? AND filename = ?
                   ORDER BY build_root, project_path""",
            ).use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
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
            val (prefixId, filename) = pathCodec.encodeIfInterned(path) ?: return emptySet()
            return conn.prepareStatement(
                """SELECT source_sets.build_root, source_sets.project_path, source_sets.source_set_name,
                          projects.build_root AS owner_build_root
                   FROM file_gradle_source_sets source_sets
                   LEFT JOIN file_gradle_projects projects
                     ON projects.prefix_id = source_sets.prefix_id
                    AND projects.filename = source_sets.filename
                    AND projects.build_root = source_sets.build_root
                    AND projects.project_path = source_sets.project_path
                   WHERE source_sets.prefix_id = ? AND source_sets.filename = ?
                   ORDER BY source_sets.build_root, source_sets.project_path, source_sets.source_set_name""",
            ).use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
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
            val (prefixId, filename) = pathCodec.encodeIfInterned(path) ?: return null
            return conn.prepareStatement(
                """SELECT metadata.package_state, metadata.package_unproven_reason,
                          metadata.package_fq_id, packages.fq_name
                   FROM file_metadata metadata
                   LEFT JOIN fq_names packages ON packages.fq_id = metadata.package_fq_id
                   WHERE metadata.prefix_id = ? AND metadata.filename = ?""",
            ).use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
                val rs = stmt.executeQuery()
                if (!rs.next()) return@use null
                mutations.decodePackageEvidence(rs)
            }
        }
    }

}
