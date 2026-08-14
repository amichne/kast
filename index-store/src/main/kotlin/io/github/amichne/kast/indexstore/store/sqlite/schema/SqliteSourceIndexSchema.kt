package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import java.sql.Connection

internal class SqliteSourceIndexSchema(
    private val state: SqliteSourceIndexStoreState,
    private val tables: SourceIndexSchemaTables = SourceIndexSchemaTables(),
) {
    fun ensureSchema(): Boolean {
        synchronized(state.writeLock) {
            val conn = state.connection(requireCurrentSchema = false)
            val version = readSchemaVersion(conn)
            if (version == SOURCE_INDEX_SCHEMA_VERSION) {
                val interningTablesNeedReload = !state.isSchemaValidated(conn)
                validateCurrentSchema(conn)
                state.initializeRepositoryOverlay(conn)
                if (interningTablesNeedReload) {
                    state.reloadInterningTables(conn)
                } else {
                    state.loadInterningTables(conn)
                }
                if (conn.autoCommit) state.refreshManifestFileCount(conn)
                state.markSchemaValidated(conn)
                return true
            }
            check(conn.autoCommit) { "Source-index schema cannot regenerate during a workspace write" }
            val previousGeneration = state.readGenerationOrNullInTransaction(conn) ?: SourceIndexGeneration(0)
            conn.autoCommit = false
            try {
                dropAllTables(conn)
                createAllTables(conn)
                state.writeGenerationInTransaction(conn, SourceIndexGeneration(Math.addExact(previousGeneration.value, 1L)))
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
            validateCurrentSchema(conn)
            state.initializeRepositoryOverlay(conn)
            state.reloadInterningTables(conn)
            state.refreshManifestFileCount(conn)
            state.markSchemaValidated(conn)
            return false
        }
    }

    internal fun readSchemaVersion(conn: Connection): Int? = try {
        conn.prepareStatement("SELECT version FROM main.schema_version LIMIT 1").use { stmt ->
            stmt.executeQuery().let { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    } catch (_: Exception) {
        null
    }

    internal fun validateCurrentSchema(conn: Connection) {
        val version = readSchemaVersion(conn)
        check(version == SOURCE_INDEX_SCHEMA_VERSION) {
            "Source index schema version $version cannot be read as version $SOURCE_INDEX_SCHEMA_VERSION"
        }
        val requiredColumns = mapOf(
            "file_metadata" to mapOf(
                "prefix_id" to true,
                "filename" to true,
                "package_fq_id" to false,
                "package_state" to true,
                "package_unproven_reason" to false,
                "module_path" to false,
                "source_set" to false,
            ),
            "file_gradle_projects" to mapOf(
                "prefix_id" to true,
                "filename" to true,
                "build_root" to true,
                "project_path" to true,
            ),
            "file_gradle_source_sets" to mapOf(
                "prefix_id" to true,
                "filename" to true,
                "build_root" to true,
                "project_path" to true,
                "source_set_name" to true,
            ),
            "file_manifest" to mapOf(
                "prefix_id" to true,
                "filename" to true,
                "last_modified_millis" to true,
                "content_hash" to false,
                "desired_source_version" to false,
                "desired_relationships_version" to false,
                "desired_semantic_graph_version" to false,
                "module_name" to false,
                "source_set" to false,
            ),
            "file_stage_outcomes" to mapOf(
                "prefix_id" to true,
                "filename" to true,
                "stage" to true,
                "content_hash" to true,
                "stage_version" to true,
                "stage_input_fingerprint" to false,
                "outcome_status" to true,
                "limitations_json" to true,
                "failure_id" to false,
                "failure_code" to false,
                "failure_message" to false,
                "failure_attempt_count" to true,
            ),
            "module_index_progress" to mapOf(
                "relationship_index_status" to true,
            ),
            "workspace_publication" to mapOf(
                "singleton" to false,
                "revision" to true,
                "identity" to true,
                "source_index_generation" to true,
                "source_revision" to true,
                "reference_revision" to true,
                "graph_revision" to false,
                "graph_blocker" to false,
                "source_index_schema_version" to true,
                "published_at_epoch_millis" to true,
                "repository_overlay_file" to false,
            ),
            "semantic_files" to mapOf(
                "id" to false,
                "path" to true,
                "content_hash" to false,
                "refresh_status" to true,
                "diagnostics_json" to true,
                "boundary_failure_id" to false,
                "boundary_failure_code" to false,
            ),
            "semantic_types" to mapOf(
                "id" to false,
                "stable_key" to true,
                "kind" to true,
                "nullability" to true,
                "debug_text" to true,
            ),
            "semantic_type_edges" to mapOf(
                "id" to false,
                "parent_type_id" to true,
                "child_type_id" to false,
                "role" to true,
                "position" to true,
                "variance" to true,
            ),
            "semantic_symbols" to mapOf(
                "id" to false,
                "stable_key" to true,
                "file_id" to true,
                "owner_id" to false,
                "kind" to true,
                "name" to true,
                "start_offset" to true,
                "end_offset" to true,
                "line" to true,
            ),
            "semantic_edge_occurrences" to mapOf(
                "id" to false,
                "source_id" to true,
                "target_id" to true,
                "source_file_id" to true,
                "kind" to true,
                "context" to true,
                "start_offset" to true,
                "end_offset" to true,
                "line" to true,
            ),
        )
        requiredColumns.forEach { (tableName, columns) ->
            val actualColumns = conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info('$tableName')")
                buildMap {
                    while (rs.next()) put(rs.getString("name"), rs.getInt("notnull") == 1)
                }
            }
            check(actualColumns.isNotEmpty()) {
                "Source index schema $SOURCE_INDEX_SCHEMA_VERSION is missing required table $tableName"
            }
            columns.forEach { (columnName, mustBeNonNull) ->
                val actualNonNull = actualColumns[columnName]
                check(actualNonNull != null) {
                    "Source index schema $SOURCE_INDEX_SCHEMA_VERSION is missing required column $tableName.$columnName"
                }
                check(!mustBeNonNull || actualNonNull) {
                    "Source index schema $SOURCE_INDEX_SCHEMA_VERSION requires $tableName.$columnName to be non-null"
                }
                if (tableName == "file_stage_outcomes" &&
                    columnName in setOf(
                        "stage_input_fingerprint",
                        "failure_id",
                        "failure_code",
                        "failure_message",
                    )
                ) {
                    check(!actualNonNull) {
                        "Source index schema $SOURCE_INDEX_SCHEMA_VERSION requires " +
                            "file_stage_outcomes.$columnName to be nullable"
                    }
                }
            }
        }
        val requiredPrimaryKeys = mapOf(
            "file_metadata" to listOf("prefix_id", "filename"),
            "file_gradle_projects" to listOf("prefix_id", "filename", "build_root", "project_path"),
            "file_gradle_source_sets" to listOf(
                "prefix_id",
                "filename",
                "build_root",
                "project_path",
                "source_set_name",
            ),
            "file_manifest" to listOf("prefix_id", "filename"),
            "file_stage_outcomes" to listOf("prefix_id", "filename", "stage"),
            "workspace_publication" to listOf("singleton"),
            "semantic_files" to listOf("id"),
            "semantic_types" to listOf("id"),
            "semantic_type_edges" to listOf("id"),
            "semantic_symbols" to listOf("id"),
            "semantic_edge_occurrences" to listOf("id"),
        )
        requiredPrimaryKeys.forEach { (tableName, requiredPrimaryKey) ->
            val actualPrimaryKey = conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info('$tableName')")
                buildList {
                    while (rs.next()) {
                        val position = rs.getInt("pk")
                        if (position > 0) add(position to rs.getString("name"))
                    }
                }.sortedBy { (position, _) -> position }.map { (_, columnName) -> columnName }
            }
            check(actualPrimaryKey == requiredPrimaryKey) {
                "Source index schema $SOURCE_INDEX_SCHEMA_VERSION has invalid primary key for $tableName"
            }
        }
        val metadataTableSql = conn.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'file_metadata'",
        ).use { stmt ->
            val rs = stmt.executeQuery()
            check(rs.next()) { "Source index schema is missing file_metadata" }
            checkNotNull(rs.getString(1)) { "Source index schema has no file_metadata definition" }
        }.uppercase().filterNot(Char::isWhitespace)
        val requiredConstraintFragments = listOf(
            "PACKAGE_STATEIN('PROVEN_ROOT','PROVEN_NAMED','UNPROVEN')",
            "PACKAGE_STATE='PROVEN_ROOT'ANDPACKAGE_FQ_IDISNULLANDPACKAGE_UNPROVEN_REASONISNULL",
            "PACKAGE_STATE='PROVEN_NAMED'ANDPACKAGE_FQ_IDISNOTNULLANDPACKAGE_UNPROVEN_REASONISNULL",
            "PACKAGE_STATE='UNPROVEN'ANDPACKAGE_FQ_IDISNULLANDPACKAGE_UNPROVEN_REASONIN(" +
                "'NOT_SCANNED','SEMANTIC_ANALYSIS_UNAVAILABLE','SEMANTIC_ANALYSIS_FAILED','LEGACY_TEXT_ONLY')",
        )
        requiredConstraintFragments.forEach { fragment ->
            check(fragment in metadataTableSql) {
                "Source index schema $SOURCE_INDEX_SCHEMA_VERSION lacks required package provenance constraints"
            }
        }
        val requiredForeignKeys = mapOf(
            "file_metadata" to setOf("fq_names|NO ACTION|package_fq_id->fq_id"),
            "file_gradle_projects" to setOf(
                "file_metadata|CASCADE|prefix_id->prefix_id,filename->filename",
            ),
            "file_gradle_source_sets" to setOf(
                "file_gradle_projects|CASCADE|" +
                    "prefix_id->prefix_id,filename->filename,build_root->build_root,project_path->project_path",
            ),
        )
        requiredForeignKeys.forEach { (tableName, required) ->
            val actual = foreignKeySignatures(conn, tableName)
            check(actual.containsAll(required)) {
                "Source index schema $SOURCE_INDEX_SCHEMA_VERSION has invalid foreign keys for $tableName"
            }
        }
        validateDurableEvidenceSchema(conn)
    }

    private fun dropAllTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            dropSourceIndexTables(stmt)
            WorkspaceMetadataSchema.dropTables(stmt)
        }
    }

    private fun dropSourceIndexTables(stmt: java.sql.Statement) {
        stmt.execute("DROP VIEW IF EXISTS main.semantic_module_quotient")
        stmt.execute("DROP VIEW IF EXISTS main.semantic_package_quotient")
        stmt.execute("DROP VIEW IF EXISTS main.semantic_file_quotient")
        stmt.execute("DROP TRIGGER IF EXISTS main.fq_names_ai")
        stmt.execute("DROP TRIGGER IF EXISTS main.fq_names_ad")
        stmt.execute("DROP TRIGGER IF EXISTS main.fq_names_au")
        stmt.execute("DROP TABLE IF EXISTS main.fq_names_fts")
        stmt.execute("DROP TABLE IF EXISTS main.pending_updates")
        stmt.execute("DROP TABLE IF EXISTS main.module_index_progress")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_edge_occurrences")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_symbol_annotations")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_type_edges")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_symbols")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_types")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_files")
        stmt.execute("DROP TABLE IF EXISTS main.repository_overlay_state")
        stmt.execute("DROP TABLE IF EXISTS main.repository_overlay_tombstones")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_graph_relations")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_graph_symbols")
        stmt.execute("DROP TABLE IF EXISTS main.semantic_graph_files")
        stmt.execute("DROP TABLE IF EXISTS main.declaration_supertypes")
        stmt.execute("DROP TABLE IF EXISTS main.declarations")
        stmt.execute("DROP TABLE IF EXISTS main.symbol_references")
        stmt.execute("DROP TABLE IF EXISTS main.file_wildcard_imports")
        stmt.execute("DROP TABLE IF EXISTS main.file_imports")
        stmt.execute("DROP TABLE IF EXISTS main.identifier_paths")
        stmt.execute("DROP TABLE IF EXISTS main.file_stage_outcomes")
        stmt.execute("DROP TABLE IF EXISTS main.file_gradle_source_sets")
        stmt.execute("DROP TABLE IF EXISTS main.file_gradle_projects")
        stmt.execute("DROP TABLE IF EXISTS main.file_metadata")
        stmt.execute("DROP TABLE IF EXISTS main.file_manifest")
        stmt.execute("DROP TABLE IF EXISTS main.fq_names")
        stmt.execute("DROP TABLE IF EXISTS main.path_prefixes")
    }

    internal fun createAllTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            WorkspaceMetadataSchema.createTables(stmt)
            tables.createPathPrefixTable(stmt)
            tables.createFqNameTable(stmt)
            tables.createFqNameSearchIndex(stmt)
            tables.createSourceIndexTables(stmt)
            createSourceIndexIndexes(stmt)

        }
    }

    private fun createSourceIndexIndexes(conn: Connection) {
        conn.createStatement().use { stmt -> createSourceIndexIndexes(stmt) }
    }

    private fun createSourceIndexIndexes(stmt: java.sql.Statement) {
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_ip_prefix_file ON identifier_paths(prefix_id, filename)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_module_path ON file_metadata(module_path)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_source_set ON file_metadata(source_set)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_module_path_source_set ON file_metadata(module_path, source_set)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_metadata_package ON file_metadata(package_fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_gradle_projects_project ON file_gradle_projects(build_root, project_path)")
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_file_gradle_source_sets_identity " +
                "ON file_gradle_source_sets(build_root, project_path, source_set_name)",
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_imports_fq ON file_imports(fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_wildcard_imports_fq ON file_wildcard_imports(fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_target ON symbol_references(target_fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_source ON symbol_references(src_prefix_id, src_filename)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_source_fq ON symbol_references(source_fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_edge_kind ON symbol_references(edge_kind)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_symref_target_file ON symbol_references(tgt_prefix_id, tgt_filename)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_declarations_module ON declarations(module_path)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_declarations_visibility ON declarations(visibility)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_declarations_kind ON declarations(kind)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_declarations_file ON declarations(prefix_id, filename)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_decl_supertypes_supertype ON declaration_supertypes(supertype_fq_id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_updates_unapplied ON pending_updates(applied, seq)")
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_file_stage_outcomes_stage " +
                "ON file_stage_outcomes(stage, prefix_id, filename)",
        )
        stmt.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_file_stage_outcomes_failure_id " +
                "ON file_stage_outcomes(failure_id) WHERE failure_id IS NOT NULL",
        )
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_semantic_files_package_status_id " +
                "ON semantic_files(package_name, refresh_status, id)",
        )
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_semantic_files_module_id " +
                "ON semantic_files(module_name, id)",
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_semantic_symbols_file_id_id ON semantic_symbols(file_id, id)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_semantic_symbols_owner_id_id ON semantic_symbols(owner_id, id)")
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_semantic_edges_source_file_id_id " +
                "ON semantic_edge_occurrences(source_file_id, id)",
        )
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_semantic_edges_source_kind_target " +
                "ON semantic_edge_occurrences(source_id, kind, target_id)",
        )
        stmt.execute(
            "CREATE INDEX IF NOT EXISTS idx_semantic_edges_target_kind_source " +
                "ON semantic_edge_occurrences(target_id, kind, source_id)",
        )
    }

}
