package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.LineNumber
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.result.SemanticGraphDiagnosticEvidence
import io.github.amichne.kast.api.contract.result.SemanticGraphFileCoverage
import io.github.amichne.kast.api.contract.result.SemanticGraphFileStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphRelation
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationContext
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationKind
import io.github.amichne.kast.api.contract.result.SemanticGraphSha256
import io.github.amichne.kast.api.contract.result.SemanticGraphSourcePath
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphIndexSnapshot
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.SourceIndexSnapshot
import io.github.amichne.kast.indexstore.api.index.SourceIndexWriter
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.api.reference.GeneratedSymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.store.cache.defaultCacheJson
import io.github.amichne.kast.indexstore.store.codec.PathInterningCodec
import io.github.amichne.kast.indexstore.store.codec.StringInterningCodec
import io.github.amichne.kast.indexstore.store.jdbc.SqliteJdbcDriverBootstrap
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite-backed store for the source identifier index, file manifest,
 * symbol references, and workspace discovery cache.
 *
 * All data lives in a single `source-index.db` database under the kast cache
 * directory. WAL journal mode is enabled so readers never block writers.
 */
class SqliteSourceIndexStore private constructor(
    workspaceIdentity: WorkspaceIdentity,
    private val pageReadObserver: SourceIndexPageReadObserver,
) : AutoCloseable, SourceIndexWriter {
    constructor(workspaceIdentity: WorkspaceIdentity) : this(workspaceIdentity, SourceIndexPageReadObserver.Disabled)

    constructor(workspaceRoot: Path) : this(WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot))

    internal constructor(
        workspaceRoot: Path,
        pageReadObserver: SourceIndexPageReadObserver,
    ) : this(WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot), pageReadObserver)

    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    private val dbPath: Path = workspaceIdentity.sourceIndexDatabaseFile
    private val pathCodec = PathInterningCodec(workspaceRoot)
    private val fqCodec = StringInterningCodec(
        tableName = "fq_names",
        idColumn = "fq_id",
        valueColumn = "fq_name",
    )
    private val connectionLock = Any()
    private val writeLock = Any()

    @Volatile
    private var cachedConnection: Connection? = null

    @Volatile
    private var validatedSchemaConnection: Connection? = null

    fun dbExists(): Boolean = Files.isRegularFile(dbPath)

    private fun connection(requireCurrentSchema: Boolean = true): Connection {
        cachedConnection?.let { conn ->
            if (!conn.isClosed && Files.isRegularFile(dbPath)) {
                if (!requireCurrentSchema || validatedSchemaConnection === conn) return conn
            }
        }
        synchronized(connectionLock) {
            cachedConnection?.let { conn ->
                if (!conn.isClosed && Files.isRegularFile(dbPath)) {
                    if (requireCurrentSchema && validatedSchemaConnection !== conn) {
                        validateCurrentSchema(conn)
                        validatedSchemaConnection = conn
                    }
                    return conn
                }
                // DB file was deleted (e.g. by CacheManager.invalidateAll()) while
                // the connection was still open. Close the orphaned connection so
                // the next call creates a fresh file.
                runCatching { conn.close() }
                cachedConnection = null
                validatedSchemaConnection = null
            }
            Files.createDirectories(dbPath.parent)
            SqliteJdbcDriverBootstrap.ensureRegistered()
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA synchronous=NORMAL")
                    stmt.execute("PRAGMA busy_timeout=5000")
                    stmt.execute("PRAGMA cache_size=-64000")
                    stmt.execute("PRAGMA mmap_size=268435456")
                    stmt.execute("PRAGMA temp_store=MEMORY")
                    stmt.execute("PRAGMA wal_autocheckpoint=1000")
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
                if (readSchemaVersion(conn) == null) {
                    conn.autoCommit = false
                    createAllTables(conn)
                    conn.commit()
                    conn.autoCommit = true
                }
                if (requireCurrentSchema) {
                    validateCurrentSchema(conn)
                    loadInterningTables(conn)
                }
                cachedConnection = conn
                validatedSchemaConnection = conn.takeIf { requireCurrentSchema }
                return conn
            } catch (e: Exception) {
                if (!conn.autoCommit) runCatching { conn.rollback() }
                runCatching { conn.close() }
                throw e
            } finally {
                if (!conn.isClosed) conn.autoCommit = true
            }
        }
    }

    override fun close() {
        synchronized(connectionLock) {
            cachedConnection?.let { conn ->
                runCatching { conn.close() }
                cachedConnection = null
                validatedSchemaConnection = null
            }
        }
    }

    /**
     * Ensures the database schema is present and at the current version.
     *
     * @return `true` if the existing schema was valid, `false` if tables were
     * dropped and recreated.
     */
    fun ensureSchema(): Boolean {
        synchronized(writeLock) {
            val conn = connection(requireCurrentSchema = false)
            val version = readSchemaVersion(conn)
            if (version == SOURCE_INDEX_SCHEMA_VERSION) {
                validateCurrentSchema(conn)
                validatedSchemaConnection = conn
                loadInterningTables(conn)
                return true
            }
            val previousGeneration = readGenerationOrNullInTransaction(conn) ?: SourceIndexGeneration(0)
            conn.autoCommit = false
            try {
                dropAllTables(conn)
                createAllTables(conn)
                writeGenerationInTransaction(conn, SourceIndexGeneration(Math.addExact(previousGeneration.value, 1L)))
                conn.commit()
                validateCurrentSchema(conn)
                validatedSchemaConnection = conn
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
            loadInterningTables(conn)
            return false
        }
    }

    private fun readSchemaVersion(conn: Connection): Int? = try {
        conn.prepareStatement("SELECT version FROM schema_version LIMIT 1").use { stmt ->
            stmt.executeQuery().let { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    } catch (_: Exception) {
        null
    }

    private fun validateCurrentSchema(conn: Connection) {
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
            "semantic_graph_files" to mapOf(
                "path" to true,
                "content_hash" to true,
                "refresh_status" to true,
                "diagnostics_json" to true,
            ),
            "semantic_graph_symbols" to mapOf(
                "canonical_key" to true,
                "kind" to true,
                "name" to true,
                "path" to true,
                "start_offset" to true,
                "end_offset" to true,
                "line" to true,
            ),
            "semantic_graph_relations" to mapOf(
                "source_key" to true,
                "target_key" to true,
                "resolved_target_key" to false,
                "kind" to true,
                "context" to true,
                "source_path" to true,
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
            "semantic_graph_files" to listOf("path"),
            "semantic_graph_symbols" to listOf("canonical_key"),
            "semantic_graph_relations" to listOf(
                "source_key",
                "target_key",
                "kind",
                "context",
                "source_path",
                "start_offset",
            ),
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
    }

    private fun foreignKeySignatures(conn: Connection, tableName: String): Set<String> {
        val columnsById = mutableMapOf<Int, MutableList<Triple<Int, String, String>>>()
        val targetTableById = mutableMapOf<Int, String>()
        val onDeleteById = mutableMapOf<Int, String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA foreign_key_list('$tableName')")
            while (rs.next()) {
                val id = rs.getInt("id")
                columnsById.getOrPut(id) { mutableListOf() }.add(
                    Triple(rs.getInt("seq"), rs.getString("from"), rs.getString("to")),
                )
                targetTableById[id] = rs.getString("table")
                onDeleteById[id] = rs.getString("on_delete")
            }
        }
        return columnsById.mapTo(mutableSetOf()) { (id, columns) ->
            val mappings = columns.sortedBy { (position, _, _) -> position }.joinToString(",") { (_, from, to) ->
                "$from->$to"
            }
            "${targetTableById.getValue(id)}|${onDeleteById.getValue(id)}|$mappings"
        }
    }

    private fun dropAllTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            dropSourceIndexTables(stmt)
            stmt.execute("DROP TABLE IF EXISTS schema_version")
            stmt.execute("DROP TABLE IF EXISTS workspace_discovery")
        }
    }

    private fun dropSourceIndexTables(stmt: java.sql.Statement) {
        stmt.execute("DROP TRIGGER IF EXISTS fq_names_ai")
        stmt.execute("DROP TRIGGER IF EXISTS fq_names_ad")
        stmt.execute("DROP TRIGGER IF EXISTS fq_names_au")
        stmt.execute("DROP TABLE IF EXISTS fq_names_fts")
        stmt.execute("DROP TABLE IF EXISTS pending_updates")
        stmt.execute("DROP TABLE IF EXISTS module_index_progress")
        stmt.execute("DROP TABLE IF EXISTS semantic_graph_relations")
        stmt.execute("DROP TABLE IF EXISTS semantic_graph_symbols")
        stmt.execute("DROP TABLE IF EXISTS semantic_graph_files")
        stmt.execute("DROP TABLE IF EXISTS declaration_supertypes")
        stmt.execute("DROP TABLE IF EXISTS declarations")
        stmt.execute("DROP TABLE IF EXISTS symbol_references")
        stmt.execute("DROP TABLE IF EXISTS file_wildcard_imports")
        stmt.execute("DROP TABLE IF EXISTS file_imports")
        stmt.execute("DROP TABLE IF EXISTS identifier_paths")
        stmt.execute("DROP TABLE IF EXISTS file_gradle_source_sets")
        stmt.execute("DROP TABLE IF EXISTS file_gradle_projects")
        stmt.execute("DROP TABLE IF EXISTS file_metadata")
        stmt.execute("DROP TABLE IF EXISTS file_manifest")
        stmt.execute("DROP TABLE IF EXISTS fq_names")
        stmt.execute("DROP TABLE IF EXISTS path_prefixes")
    }

    private fun createAllTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL,
                    generation INTEGER NOT NULL DEFAULT 0,
                    head_commit TEXT
                )""",
            )
            stmt.execute("INSERT INTO schema_version (version, generation, head_commit) VALUES ($SOURCE_INDEX_SCHEMA_VERSION, 0, NULL)")

            createPathPrefixTable(stmt)
            createFqNameTable(stmt)
            createFqNameSearchIndex(stmt)
            createSourceIndexTables(stmt)
            createSourceIndexIndexes(stmt)

            stmt.execute(
                """CREATE TABLE IF NOT EXISTS workspace_discovery (
                    cache_key TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    payload TEXT NOT NULL
                )""",
            )
        }
    }

    private fun createPathPrefixTable(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS path_prefixes (
                prefix_id INTEGER PRIMARY KEY,
                dir_path TEXT NOT NULL UNIQUE
            )""",
        )
    }

    private fun createFqNameTable(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS fq_names (
                fq_id INTEGER PRIMARY KEY,
                fq_name TEXT NOT NULL UNIQUE
            )""",
        )
    }

    private fun createFqNameSearchIndex(stmt: java.sql.Statement) {
        stmt.execute("""CREATE VIRTUAL TABLE IF NOT EXISTS fq_names_fts USING fts5(fq_name, tokenize='trigram')""")
        stmt.execute(
            """CREATE TRIGGER IF NOT EXISTS fq_names_ai
               AFTER INSERT ON fq_names BEGIN
                   INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
               END""",
        )
        stmt.execute(
            """CREATE TRIGGER IF NOT EXISTS fq_names_ad
               AFTER DELETE ON fq_names BEGIN
                   DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
               END""",
        )
        stmt.execute(
            """CREATE TRIGGER IF NOT EXISTS fq_names_au
               AFTER UPDATE OF fq_name ON fq_names BEGIN
                   DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
                   INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
               END""",
        )
    }

    private fun createSourceIndexTables(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS identifier_paths (
                identifier TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                PRIMARY KEY (identifier, prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_metadata (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                package_fq_id INTEGER,
                package_state TEXT NOT NULL CHECK(package_state IN ('PROVEN_ROOT','PROVEN_NAMED','UNPROVEN')),
                package_unproven_reason TEXT,
                module_path TEXT,
                source_set TEXT,
                PRIMARY KEY (prefix_id, filename),
                CHECK(
                    (package_state = 'PROVEN_ROOT' AND package_fq_id IS NULL AND package_unproven_reason IS NULL)
                    OR (package_state = 'PROVEN_NAMED' AND package_fq_id IS NOT NULL AND package_unproven_reason IS NULL)
                    OR (
                        package_state = 'UNPROVEN'
                        AND package_fq_id IS NULL
                        AND package_unproven_reason IN (
                            'NOT_SCANNED',
                            'SEMANTIC_ANALYSIS_UNAVAILABLE',
                            'SEMANTIC_ANALYSIS_FAILED',
                            'LEGACY_TEXT_ONLY'
                        )
                    )
                ),
                FOREIGN KEY(package_fq_id) REFERENCES fq_names(fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_gradle_projects (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                build_root TEXT NOT NULL,
                project_path TEXT NOT NULL,
                PRIMARY KEY (prefix_id, filename, build_root, project_path),
                FOREIGN KEY(prefix_id, filename) REFERENCES file_metadata(prefix_id, filename) ON DELETE CASCADE
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_gradle_source_sets (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                build_root TEXT NOT NULL,
                project_path TEXT NOT NULL,
                source_set_name TEXT NOT NULL,
                PRIMARY KEY (prefix_id, filename, build_root, project_path, source_set_name),
                FOREIGN KEY(prefix_id, filename, build_root, project_path)
                    REFERENCES file_gradle_projects(prefix_id, filename, build_root, project_path)
                    ON DELETE CASCADE
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_imports (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename, fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_wildcard_imports (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename, fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_manifest (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                last_modified_millis INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS symbol_references (
                src_prefix_id INTEGER NOT NULL,
                src_filename TEXT NOT NULL,
                source_offset INTEGER NOT NULL,
                source_fq_id INTEGER,
                target_fq_id INTEGER NOT NULL,
                tgt_prefix_id INTEGER,
                tgt_filename TEXT,
                target_offset INTEGER,
                edge_kind TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(edge_kind IN ('CALL','TYPE_REF','INHERITANCE','OVERRIDE','IMPORT','ANNOTATION','UNKNOWN')),
                PRIMARY KEY (src_prefix_id, src_filename, source_offset, target_fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS declarations (
                fq_id INTEGER NOT NULL,
                kind TEXT NOT NULL CHECK(kind IN ('CLASS','INTERFACE','OBJECT','FUNCTION','PROPERTY','TYPEALIAS','ENUM_CLASS','ENUM_ENTRY','CONSTRUCTOR')),
                visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC','INTERNAL','PROTECTED','PRIVATE','LOCAL')),
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                declaration_offset INTEGER,
                module_path TEXT,
                source_set TEXT,
                PRIMARY KEY (fq_id, prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS declaration_supertypes (
                declaration_fq_id INTEGER NOT NULL,
                supertype_fq_id INTEGER NOT NULL,
                PRIMARY KEY (declaration_fq_id, supertype_fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS pending_updates (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                op TEXT NOT NULL CHECK(op IN ('upsert_file','remove_file','upsert_ref','remove_ref')),
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                payload TEXT,
                session_id TEXT,
                epoch_ms INTEGER NOT NULL,
                applied INTEGER NOT NULL DEFAULT 0
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS module_index_progress (
                module_name TEXT PRIMARY KEY,
                phase2_status TEXT NOT NULL DEFAULT 'PENDING' CHECK(phase2_status IN ('PENDING','INDEXING','COMPLETE','FAILED')),
                indexed_file_count INTEGER NOT NULL DEFAULT 0,
                total_file_count INTEGER NOT NULL DEFAULT 0,
                last_indexed_epoch_ms INTEGER
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_graph_files (
                path TEXT PRIMARY KEY NOT NULL,
                content_hash TEXT NOT NULL,
                refresh_status TEXT NOT NULL CHECK(refresh_status IN ('REFRESHED','CACHED','REMOVED')),
                diagnostics_json TEXT NOT NULL
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_graph_symbols (
                canonical_key TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                fq_name TEXT,
                signature TEXT,
                owner_key TEXT,
                path TEXT NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_graph_relations (
                source_key TEXT NOT NULL,
                target_key TEXT NOT NULL,
                resolved_target_key TEXT,
                kind TEXT NOT NULL,
                context TEXT NOT NULL,
                source_path TEXT NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL,
                PRIMARY KEY (source_key, target_key, kind, context, source_path, start_offset)
            )""",
        )
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
    }

    fun saveFullIndex(
        updates: List<FileIndexUpdate>,
        manifest: Map<String, Long>,
    ) {
        val eligibleUpdates = updates.filter { update -> SourceIndexFilePolicy.isEligible(update.path) }
        val eligibleManifest = manifest.filterKeys(SourceIndexFilePolicy::isEligible)
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, eligibleUpdates.map { it.path } + eligibleManifest.keys)
                internFqNamesInTransaction(conn, eligibleUpdates.flatMapTo(mutableSetOf()) { update ->
                    buildList {
                        packageFqName(update)?.let(::add)
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
                    insertFileDataInTransaction(conn, update)
                }
                insertManifestInTransaction(conn, eligibleManifest)
                pruneReferencesOutsideManifestInTransaction(conn, eligibleManifest.keys)
                removeIneligibleSourceIndexRows(conn)
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM pending_updates") }
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun saveFileIndex(update: FileIndexUpdate) {
        if (!SourceIndexFilePolicy.isEligible(update.path)) {
            removeFile(update.path)
            return
        }
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, listOf(update.path))
                internFqNamesInTransaction(conn, fqNamesFor(update))
                insertFileDataInTransaction(conn, update)
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun removeFile(path: String) {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            val encodedPath = pathCodec.encodeIfInterned(path) ?: return
            conn.autoCommit = false
            try {
                deleteFileRowsInTransaction(conn, encodedPath.first, encodedPath.second)
                incrementGenerationInTransaction(conn)
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
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
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

            loadFileFqNames(conn, "file_imports", importsByPath)
            loadFileFqNames(conn, "file_wildcard_imports", wildcardImportPackagesByPath)

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
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
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
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
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
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
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
                decodePackageEvidence(rs)
            }
        }
    }

    fun saveManifest(entries: Map<String, Long>) {
        val eligibleEntries = entries.filterKeys(SourceIndexFilePolicy::isEligible)
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, eligibleEntries.keys)
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM file_manifest") }
                insertManifestInTransaction(conn, eligibleEntries)
                removeIneligibleSourceIndexRows(conn)
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun updateManifestEntry(
        path: String,
        lastModifiedMillis: Long,
    ) {
        if (!SourceIndexFilePolicy.isEligible(path)) {
            removeFile(path)
            return
        }
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, listOf(path))
                val (prefixId, filename) = pathCodec.encode(path)
                conn.prepareStatement(
                    """INSERT OR REPLACE INTO file_manifest (prefix_id, filename, last_modified_millis)
                       VALUES (?, ?, ?)""",
                ).use { stmt ->
                    stmt.setInt(1, prefixId)
                    stmt.setString(2, filename)
                    stmt.setLong(3, lastModifiedMillis)
                    stmt.executeUpdate()
                }
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun loadManifest(): Map<String, Long>? {
        if (!dbExists()) return null
        return synchronized(writeLock) {
            try {
                val conn = connection()
                loadInterningTables(conn)
                buildMap {
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery("SELECT prefix_id, filename, last_modified_millis FROM file_manifest")
                        while (rs.next()) put(pathCodec.decode(rs.getInt(1), rs.getString(2)), rs.getLong(3))
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun knownSourcePaths(): List<Path> {
        if (!dbExists()) return emptyList()
        return synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT prefix_id, filename FROM file_manifest")
                buildList {
                    while (rs.next()) {
                        val path = Path.of(pathCodec.decode(rs.getInt(1), rs.getString(2)))
                            .toAbsolutePath()
                            .normalize()
                        if (Files.isRegularFile(path) && SourceIndexFilePolicy.isEligible(path)) {
                            add(path)
                        }
                    }
                }.distinct().sorted()
            }
        }
    }

    fun fileCountBySourceRoot(sourceRoots: Collection<Path>): Map<Path, Int> {
        val roots = normalizedSourceRoots(sourceRoots)
        if (roots.isEmpty()) return emptyMap()
        if (!dbExists()) return roots.associateWith { 0 }

        return synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            val countsByDir = conn.prepareStatement(
                """SELECT prefixes.dir_path, COUNT(*) AS file_count
                   FROM file_manifest manifest
                   JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
                   GROUP BY prefixes.dir_path""",
            ).use { stmt ->
                val rs = stmt.executeQuery()
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("dir_path"), rs.getInt("file_count"))
                    }
                }
            }
            roots.associateWith { root ->
                val rootDir = sourceRootDirKey(root)
                countsByDir.entries.sumOf { (dir, count) ->
                    if (dirIsWithinSourceRoot(dir, rootDir)) count else 0
                }
            }
        }
    }

    fun filesBySourceRoot(
        sourceRoots: Collection<Path>,
        limitPerRoot: Int? = null,
    ): Map<Path, List<Path>> {
        val roots = normalizedSourceRoots(sourceRoots)
        if (roots.isEmpty()) return emptyMap()
        if (!dbExists()) return roots.associateWith { emptyList() }

        return synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            roots.associateWithTo(linkedMapOf()) { root ->
                val rootDir = sourceRootDirKey(root)
                val rows = conn.prepareStatement(sourceRootFilesSql(rootDir, limitPerRoot)).use { stmt ->
                    bindSourceRootPrefix(stmt, rootDir)
                    if (limitPerRoot != null) {
                        stmt.setInt(if (rootDir.isEmpty()) 2 else 3, limitPerRoot)
                    }
                    val rs = stmt.executeQuery()
                    buildList {
                        while (rs.next()) {
                            val path = Path.of(pathCodec.decode(rs.getInt("prefix_id"), rs.getString("filename")))
                                .toAbsolutePath()
                                .normalize()
                            if (Files.isRegularFile(path) && SourceIndexFilePolicy.isEligible(path)) {
                                add(path)
                            }
                        }
                    }
                }
                rows.distinct().sorted()
            }
        }
    }

    fun initializeModuleProgress(modules: Map<String, Int>) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM module_index_progress") }
                conn.prepareStatement(
                    """INSERT INTO module_index_progress
                       (module_name, phase2_status, indexed_file_count, total_file_count, last_indexed_epoch_ms)
                       VALUES (?, 'PENDING', 0, ?, NULL)""",
                ).use { stmt ->
                    modules.toSortedMap().forEach { (moduleName, totalFileCount) ->
                        stmt.setString(1, moduleName)
                        stmt.setInt(2, totalFileCount)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun markModuleIndexing(moduleName: String) {
        synchronized(writeLock) {
            connection().prepareStatement(
                """UPDATE module_index_progress
                   SET phase2_status = 'INDEXING'
                   WHERE module_name = ? AND phase2_status != 'COMPLETE'""",
            ).use { stmt ->
                stmt.setString(1, moduleName)
                stmt.executeUpdate()
            }
        }
    }

    fun markModuleComplete(moduleName: String, fileCount: Int) {
        synchronized(writeLock) {
            connection().prepareStatement(
                """UPDATE module_index_progress
                   SET phase2_status = 'COMPLETE',
                       indexed_file_count = ?,
                       last_indexed_epoch_ms = ?
                   WHERE module_name = ?""",
            ).use { stmt ->
                stmt.setInt(1, fileCount)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setString(3, moduleName)
                stmt.executeUpdate()
            }
        }
    }

    fun moduleIndexStatus(moduleName: String): String? =
        synchronized(writeLock) {
            connection().prepareStatement(
                "SELECT phase2_status FROM module_index_progress WHERE module_name = ?",
            ).use { stmt ->
                stmt.setString(1, moduleName)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString(1) else null
            }
        }

    fun completedModules(): Set<String> =
        synchronized(writeLock) {
            connection().createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT module_name FROM module_index_progress WHERE phase2_status = 'COMPLETE'",
                )
                buildSet {
                    while (rs.next()) {
                        add(rs.getString(1))
                    }
                }
            }
        }

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
            removeFile(sourcePath)
            return
        }
        val eligibleTargetPath = targetPath?.takeIf(SourceIndexFilePolicy::isEligible)
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, listOfNotNull(sourcePath, eligibleTargetPath))
                internFqNamesInTransaction(conn, listOfNotNull(targetFqName, sourceFqName).toSet())
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
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun upsertSymbolReferenceInTransaction(
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

    fun referencesToSymbol(targetFqName: String): List<SymbolReferenceRow> {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            val targetFqId = fqCodec.idFor(targetFqName) ?: return emptyList()
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset, edge_kind
                   FROM symbol_references
                   WHERE target_fq_id = ?""",
            ).use { stmt ->
                stmt.setInt(1, targetFqId)
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
                                targetPath = decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
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
    ): GeneratedSymbolReferencePage {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                loadInterningTables(conn)
                val generation = readGenerationInTransaction(conn)
                pageReadObserver.generationRead()
                val targetFqId = fqCodec.idFor(targetFqName)
                val page = if (targetFqId == null) {
                    SymbolReferencePage(references = emptyList(), nextOffset = null)
                } else {
                    conn.prepareStatement(
                        """SELECT refs.src_prefix_id, refs.src_filename, refs.source_offset,
                                  refs.source_fq_id, refs.target_fq_id, refs.tgt_prefix_id,
                                  refs.tgt_filename, refs.target_offset, refs.edge_kind
                           FROM symbol_references refs
                           JOIN path_prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                           WHERE refs.target_fq_id = ?
                           ORDER BY prefixes.dir_path, refs.src_filename, refs.source_offset
                           LIMIT ? OFFSET ?""",
                    ).use { stmt ->
                        stmt.setInt(1, targetFqId)
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
                                        targetPath = decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
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
                conn.commit()
                return GeneratedSymbolReferencePage(page = page, generation = generation)
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun generatedReferencePageToExactSymbol(
        target: ExactReferenceTarget,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): GeneratedSymbolReferencePage {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                loadInterningTables(conn)
                val generation = readGenerationInTransaction(conn)
                pageReadObserver.generationRead()
                val targetFqId = fqCodec.idFor(target.fqName)
                val targetPath = pathCodec.encodeIfInterned(target.declarationFile.value)
                val exactIdentityAvailable = targetFqId == null || conn.prepareStatement(
                    """SELECT NOT EXISTS(
                           SELECT 1 FROM symbol_references
                           WHERE target_fq_id = ?
                             AND (tgt_prefix_id IS NULL OR tgt_filename IS NULL OR target_offset IS NULL)
                       )""",
                ).use { stmt ->
                    stmt.setInt(1, targetFqId)
                    stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
                val page = if (targetFqId == null || targetPath == null) {
                    SymbolReferencePage(references = emptyList(), nextOffset = null)
                } else {
                    conn.prepareStatement(
                        """SELECT refs.src_prefix_id, refs.src_filename, refs.source_offset,
                                  refs.source_fq_id, refs.target_fq_id, refs.tgt_prefix_id,
                                  refs.tgt_filename, refs.target_offset, refs.edge_kind
                           FROM symbol_references refs
                           JOIN path_prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                           WHERE refs.target_fq_id = ?
                             AND refs.tgt_prefix_id = ?
                             AND refs.tgt_filename = ?
                             AND refs.target_offset = ?
                           ORDER BY prefixes.dir_path, refs.src_filename, refs.source_offset
                           LIMIT ? OFFSET ?""",
                    ).use { stmt ->
                        stmt.setInt(1, targetFqId)
                        stmt.setInt(2, targetPath.first)
                        stmt.setString(3, targetPath.second)
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
                                        targetPath = decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
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
                conn.commit()
                return GeneratedSymbolReferencePage(
                    page = page,
                    generation = generation,
                    exactIdentityAvailable = exactIdentityAvailable,
                )
            } catch (error: Exception) {
                runCatching { conn.rollback() }
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun referencesFromFile(sourcePath: String): List<SymbolReferenceRow> {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            val (prefixId, filename) = pathCodec.encodeIfInterned(sourcePath) ?: return emptyList()
            return conn.prepareStatement(
                """SELECT src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id,
                          tgt_prefix_id, tgt_filename, target_offset, edge_kind
                   FROM symbol_references
                   WHERE src_prefix_id = ? AND src_filename = ?""",
            ).use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
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
                                targetPath = decodeNullablePath(rs, prefixColumn = 6, filenameColumn = 7),
                                targetOffset = rs.getNullableInt(8),
                                edgeKind = EdgeKind.valueOf(rs.getString(9)),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun clearReferencesFromFile(sourcePath: String) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                clearReferencesFromFileInTransaction(conn, sourcePath)
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun clearReferencesFromFileInTransaction(
        conn: Connection,
        sourcePath: String,
    ) {
        loadInterningTables(conn)
        val (prefixId, filename) = pathCodec.encodeIfInterned(sourcePath) ?: return
        conn.prepareStatement("DELETE FROM symbol_references WHERE src_prefix_id = ? AND src_filename = ?")
            .use { stmt ->
                stmt.setInt(1, prefixId)
                stmt.setString(2, filename)
            stmt.executeUpdate()
        }
    }

    fun removeReferencesOutsideSources(sourcePaths: Collection<String>) {
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                if (sourcePaths.isEmpty()) {
                    conn.createStatement().use { stmt -> stmt.execute("DELETE FROM symbol_references") }
                } else {
                    loadInterningTables(conn)
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
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
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
        synchronized(writeLock) {
            val conn = connection()
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
                internPathsInTransaction(conn, pathsToIntern)
                internFqNamesInTransaction(
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
                removeIneligibleSourceIndexRows(conn)
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

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
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            try {
                internPathsInTransaction(conn, eligibleDeclarationsBySource.map { it.first })
                internFqNamesInTransaction(
                    conn,
                    eligibleDeclarationsBySource.flatMapTo(mutableSetOf()) { (_, declarations) ->
                        declarations.map { it.fqName }
                    },
                )
                for ((filePath, declarations) in eligibleDeclarationsBySource) {
                    clearDeclarationsFromFileInTransaction(conn, filePath)
                    declarations.forEach { declaration -> insertDeclarationInTransaction(conn, declaration) }
                }
                removeIneligibleSourceIndexRows(conn)
                incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun declarationsWithSupertype(supertypeFqName: String): List<DeclarationRow> {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
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

    fun appendPendingUpdate(
        op: String,
        path: String,
        payload: String?,
        sessionId: String? = null,
    ) {
        synchronized(writeLock) {
            val conn = connection()
            val (prefixId, filename) = pathCodec.encodeOrCreate(conn, path)
            conn.prepareStatement(
                """INSERT INTO pending_updates (op, prefix_id, filename, payload, session_id, epoch_ms)
                   VALUES (?, ?, ?, ?, ?, ?)""",
            ).use { stmt ->
                stmt.setString(1, op)
                stmt.setInt(2, prefixId)
                stmt.setString(3, filename)
                stmt.setString(4, payload)
                stmt.setString(5, sessionId)
                stmt.setLong(6, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    fun reconcilePendingUpdates(): Int {
        synchronized(writeLock) {
            val conn = connection()
            loadInterningTables(conn)
            conn.autoCommit = false
            return try {
                val pending = readLatestPendingUpdates(conn)
                for (update in pending) {
                    applyPendingUpdate(conn, update)
                }
                markPendingUpdatesApplied(conn, pending)
                cleanupAppliedPendingUpdates(conn)
                if (pending.isNotEmpty()) incrementGenerationInTransaction(conn)
                conn.commit()
                pending.size
            } catch (e: Exception) {
                rollbackAndReloadPrefixes(conn)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun readWorkspaceDiscovery(cacheKey: String): String? {
        synchronized(writeLock) {
            val conn = connection()
            return conn.prepareStatement(
                "SELECT payload FROM workspace_discovery WHERE cache_key = ?",
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    fun writeWorkspaceDiscovery(cacheKey: String, schemaVersion: Int, payload: String) {
        synchronized(writeLock) {
            val conn = connection()
            conn.prepareStatement(
                "INSERT OR REPLACE INTO workspace_discovery (cache_key, schema_version, payload) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                stmt.setInt(2, schemaVersion)
                stmt.setString(3, payload)
                stmt.executeUpdate()
            }
        }
    }

    fun replaceSemanticGraphFiles(
        updates: List<SemanticGraphFileIndexUpdate>,
        removedPaths: List<SemanticGraphSourcePath> = emptyList(),
    ): SourceIndexGeneration {
        require(updates.isNotEmpty() || removedPaths.isNotEmpty()) {
            "Semantic graph replacement requires an updated or removed file"
        }
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            return try {
                removedPaths.distinct().sorted().forEach { path -> deleteSemanticGraphFile(conn, path.value) }
                updates.sortedBy(SemanticGraphFileIndexUpdate::path).forEach { update ->
                    deleteSemanticGraphFile(conn, update.path.value)
                    conn.prepareStatement(
                        """INSERT INTO semantic_graph_files(path, content_hash, refresh_status, diagnostics_json)
                           VALUES (?, ?, ?, ?)""",
                    ).use { statement ->
                        statement.setString(1, update.path.value)
                        statement.setString(2, update.contentHash.value)
                        statement.setString(3, update.status.name)
                        statement.setString(4, Json.encodeToString(update.diagnostics))
                        statement.executeUpdate()
                    }
                    conn.prepareStatement(
                        """INSERT INTO semantic_graph_symbols(
                               canonical_key, kind, name, fq_name, signature, owner_key,
                               path, start_offset, end_offset, line
                           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    ).use { statement ->
                        update.symbols.sortedBy(SemanticGraphSymbol::canonicalKey).forEach { symbol ->
                            statement.setString(1, symbol.canonicalKey.value)
                            statement.setString(2, symbol.kind.name)
                            statement.setString(3, symbol.name.value)
                            statement.setString(4, symbol.fqName?.value)
                            statement.setString(5, symbol.signature?.value)
                            statement.setString(6, symbol.ownerKey?.value)
                            statement.setString(7, symbol.path.value)
                            statement.setInt(8, symbol.startOffset.value)
                            statement.setInt(9, symbol.endOffset.value)
                            statement.setInt(10, symbol.line.value)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    conn.prepareStatement(
                        """INSERT INTO semantic_graph_relations(
                               source_key, target_key, resolved_target_key, kind, context, source_path,
                               start_offset, end_offset, line
                           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    ).use { statement ->
                        update.relations.sortedWith(
                            compareBy<SemanticGraphRelation>(
                                SemanticGraphRelation::sourceKey,
                                SemanticGraphRelation::targetKey,
                                { it.kind.name },
                                { it.context.name },
                                SemanticGraphRelation::startOffset,
                            ),
                        ).forEach { relation ->
                            statement.setString(1, relation.sourceKey.value)
                            statement.setString(2, relation.targetKey.value)
                            statement.setString(3, relation.resolvedTargetKey?.value)
                            statement.setString(4, relation.kind.name)
                            statement.setString(5, relation.context.name)
                            statement.setString(6, relation.sourcePath.value)
                            statement.setInt(7, relation.startOffset.value)
                            statement.setInt(8, relation.endOffset.value)
                            statement.setInt(9, relation.line.value)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }
                incrementGenerationInTransaction(conn)
                val generation = readGenerationInTransaction(conn)
                conn.commit()
                generation
            } catch (failure: Exception) {
                conn.rollback()
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun readSemanticGraph(filePaths: Collection<SemanticGraphSourcePath>): SemanticGraphIndexSnapshot {
        val scope = filePaths.mapTo(mutableSetOf(), SemanticGraphSourcePath::value)
        synchronized(writeLock) {
            val conn = connection()
            val generation = readGenerationInTransaction(conn)
            val files = conn.prepareStatement(
                "SELECT path, content_hash, refresh_status, diagnostics_json FROM semantic_graph_files ORDER BY path",
            ).use { statement ->
                val rows = statement.executeQuery()
                buildList {
                    while (rows.next()) {
                        val path = rows.getString(1)
                        if (path in scope) {
                            add(
                                SemanticGraphFileCoverage(
                                    path = SemanticGraphSourcePath.parse(path),
                                    contentHash = rows.getString(2)?.let(SemanticGraphSha256::parse),
                                    status = SemanticGraphFileStatus.valueOf(rows.getString(3)),
                                    diagnostics = Json.decodeFromString(rows.getString(4)),
                                ),
                            )
                        }
                    }
                }
            }
            val symbols = conn.prepareStatement(
                """SELECT canonical_key, kind, name, fq_name, signature, owner_key,
                          path, start_offset, end_offset, line
                   FROM semantic_graph_symbols ORDER BY canonical_key""",
            ).use { statement ->
                val rows = statement.executeQuery()
                buildList {
                    while (rows.next()) {
                        val path = rows.getString(7)
                        if (path in scope) {
                            add(
                                SemanticGraphSymbol(
                                    canonicalKey = SemanticGraphSymbolKey.parse(rows.getString(1)),
                                    kind = SemanticGraphSymbolKind.valueOf(rows.getString(2)),
                                    name = NonBlankString(rows.getString(3)),
                                    fqName = rows.getString(4)?.let(::FqName),
                                    signature = rows.getString(5)?.let(::NonBlankString),
                                    ownerKey = rows.getString(6)?.let(SemanticGraphSymbolKey::parse),
                                    path = SemanticGraphSourcePath.parse(path),
                                    startOffset = ByteOffset(rows.getInt(8)),
                                    endOffset = ByteOffset(rows.getInt(9)),
                                    line = LineNumber(rows.getInt(10)),
                                ),
                            )
                        }
                    }
                }
            }
            val relations = conn.prepareStatement(
                """SELECT source_key, target_key, resolved_target_key, kind, context, source_path, start_offset, end_offset, line
                   FROM semantic_graph_relations
                   ORDER BY source_key, target_key, kind, context, source_path, start_offset""",
            ).use { statement ->
                val rows = statement.executeQuery()
                buildList {
                    while (rows.next()) {
                        val path = rows.getString(6)
                        if (path in scope) {
                            add(
                                SemanticGraphRelation(
                                    sourceKey = SemanticGraphSymbolKey.parse(rows.getString(1)),
                                    targetKey = SemanticGraphSymbolKey.parse(rows.getString(2)),
                                    resolvedTargetKey = rows.getString(3)?.let(SemanticGraphSymbolKey::parse),
                                    kind = SemanticGraphRelationKind.valueOf(rows.getString(4)),
                                    context = SemanticGraphRelationContext.valueOf(rows.getString(5)),
                                    sourcePath = SemanticGraphSourcePath.parse(path),
                                    startOffset = ByteOffset(rows.getInt(7)),
                                    endOffset = ByteOffset(rows.getInt(8)),
                                    line = LineNumber(rows.getInt(9)),
                                ),
                            )
                        }
                    }
                }
            }
            return SemanticGraphIndexSnapshot(generation, files, symbols, relations)
        }
    }

    fun semanticGraphSymbolKeys(): Set<SemanticGraphSymbolKey> = synchronized(writeLock) {
        connection().prepareStatement(
            "SELECT canonical_key FROM semantic_graph_symbols ORDER BY canonical_key",
        ).use { statement ->
            val rows = statement.executeQuery()
            buildSet {
                while (rows.next()) add(SemanticGraphSymbolKey.parse(rows.getString(1)))
            }
        }
    }

    private fun deleteSemanticGraphFile(conn: Connection, path: String) {
        conn.prepareStatement("DELETE FROM semantic_graph_relations WHERE source_path = ?").use { statement ->
            statement.setString(1, path)
            statement.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM semantic_graph_symbols WHERE path = ?").use { statement ->
            statement.setString(1, path)
            statement.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM semantic_graph_files WHERE path = ?").use { statement ->
            statement.setString(1, path)
            statement.executeUpdate()
        }
    }

    fun readGeneration(): SourceIndexGeneration {
        synchronized(writeLock) {
            val conn = connection()
            return try {
                readGenerationInTransaction(conn)
            } catch (_: Exception) {
                SourceIndexGeneration(0)
            }
        }
    }

    fun exportSnapshotDatabase(
        target: Path,
        treeOid: GitObjectId,
        producerVersion: ProducerVersion,
    ): PublicationEvidence = synchronized(writeLock) {
        require(!Files.exists(target)) { "Snapshot export target already exists: $target" }
        Files.createDirectories(target.toAbsolutePath().normalize().parent)
        val conn = connection()
        val generationBefore = readGenerationInTransaction(conn).value
        val (moduleProgressCount, incompleteModuleCount) = conn.createStatement().use { statement ->
            val result = statement.executeQuery(
                """SELECT COUNT(*) AS total,
                          SUM(CASE WHEN phase2_status != 'COMPLETE' OR indexed_file_count != total_file_count
                                   THEN 1 ELSE 0 END) AS incomplete
                   FROM module_index_progress""",
            )
            check(result.next())
            result.getInt("total") to result.getInt("incomplete")
        }
        val pendingCount = conn.createStatement().use { statement ->
            val result = statement.executeQuery("SELECT COUNT(*) FROM pending_updates WHERE applied = 0")
            check(result.next())
            result.getInt(1)
        }
        val escapedTarget = target.toAbsolutePath().normalize().toString().replace("'", "''")
        conn.createStatement().use { statement -> statement.execute("VACUUM INTO '$escapedTarget'") }
        val generationAfter = readGenerationInTransaction(conn).value
        PublicationEvidence(
            generationBefore = generationBefore,
            generationAfter = generationAfter,
            moduleProgressCount = moduleProgressCount,
            incompleteModuleCount = incompleteModuleCount,
            pendingCount = pendingCount,
            treeOid = treeOid,
            indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
            producerVersion = producerVersion,
        )
    }

    private fun readGenerationInTransaction(conn: Connection): SourceIndexGeneration =
        conn.prepareStatement("SELECT generation FROM schema_version LIMIT 1").use { stmt ->
            val rs = stmt.executeQuery()
            SourceIndexGeneration(if (rs.next()) rs.getLong(1) else 0L)
        }

    private fun readGenerationOrNullInTransaction(conn: Connection): SourceIndexGeneration? = try {
        conn.prepareStatement("SELECT generation FROM schema_version LIMIT 1").use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) SourceIndexGeneration(rs.getLong(1)) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun writeGenerationInTransaction(conn: Connection, generation: SourceIndexGeneration) {
        conn.prepareStatement("UPDATE schema_version SET generation = ?").use { stmt ->
            stmt.setLong(1, generation.value)
            stmt.executeUpdate()
        }
    }

    private fun incrementGenerationInTransaction(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("UPDATE schema_version SET generation = generation + 1")
        }
    }

    fun readHeadCommit(): String? {
        synchronized(writeLock) {
            val conn = connection()
            return try {
                conn.prepareStatement("SELECT head_commit FROM schema_version LIMIT 1").use { stmt ->
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getString(1) else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun writeHeadCommit(sha: String) {
        synchronized(writeLock) {
            connection().prepareStatement("UPDATE schema_version SET head_commit = ?").use { stmt ->
                stmt.setString(1, sha)
                stmt.executeUpdate()
            }
        }
    }

    private fun insertFileDataInTransaction(
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
        for (table in listOf("file_imports", "file_wildcard_imports")) {
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
        insertFileFqNamesInTransaction(conn, tableName = "file_imports", prefixId, filename, update.imports)
        insertFileFqNamesInTransaction(
            conn,
            tableName = "file_wildcard_imports",
            prefixId,
            filename,
            update.wildcardImports
        )
    }

    private fun insertManifestInTransaction(
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

    private fun pruneReferencesOutsideManifestInTransaction(
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

    private fun internPathsInTransaction(
        conn: Connection,
        paths: Iterable<String>,
    ) {
        val dirs = paths.map { pathCodec.decompose(it).first }.toSet()
        pathCodec.batchIntern(conn, dirs)
    }

    private fun internFqNamesInTransaction(
        conn: Connection,
        fqNames: Set<String>,
    ) {
        fqCodec.batchEnsure(conn, fqNames)
    }

    private fun fqNamesFor(update: FileIndexUpdate): Set<String> = buildSet {
        packageFqName(update)?.let(::add)
        addAll(update.imports)
        addAll(update.wildcardImports)
    }

    private fun packageFqName(update: FileIndexUpdate): String? =
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

    private fun decodePackageEvidence(rs: java.sql.ResultSet): IndexedPackageEvidence {
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

    private fun loadInterningTables(conn: Connection) {
        pathCodec.loadPrefixes(conn)
        fqCodec.loadAll(conn)
    }

    private fun rollbackAndReloadPrefixes(conn: Connection) {
        conn.rollback()
        runCatching { loadInterningTables(conn) }
    }

    private fun loadFileFqNames(
        conn: Connection,
        tableName: String,
        target: MutableMap<String, List<String>>,
    ) {
        val byPath = mutableMapOf<String, MutableList<String>>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT prefix_id, filename, fq_id FROM $tableName")
            while (rs.next()) {
                val path = pathCodec.decode(rs.getInt(1), rs.getString(2))
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
        tableName: String,
        prefixId: Int,
        filename: String,
        fqNames: Set<String>,
    ) {
        if (fqNames.isEmpty()) return
        fqCodec.batchEnsure(conn, fqNames)
        conn.prepareStatement("INSERT OR IGNORE INTO $tableName (prefix_id, filename, fq_id) VALUES (?, ?, ?)")
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

    private fun deleteFileRowsInTransaction(
        conn: Connection,
        prefixId: Int,
        filename: String,
    ) {
        for (table in listOf(
            "declarations",
            "identifier_paths",
            "file_gradle_source_sets",
            "file_gradle_projects",
            "file_metadata",
            "file_imports",
            "file_wildcard_imports",
            "file_manifest"
        )) {
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

    private fun readLatestPendingUpdates(conn: Connection): List<PendingUpdateRow> =
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                """SELECT p.seq, p.op, p.prefix_id, p.filename, p.payload
                   FROM pending_updates p
                   INNER JOIN (
                       SELECT prefix_id, filename, MAX(seq) AS max_seq
                       FROM pending_updates
                       WHERE applied = 0
                       GROUP BY prefix_id, filename
                   ) latest ON p.seq = latest.max_seq
                   ORDER BY p.seq""",
            )
            buildList {
                while (rs.next()) {
                    add(
                        PendingUpdateRow(
                            seq = rs.getLong(1),
                            op = rs.getString(2),
                            prefixId = rs.getInt(3),
                            filename = rs.getString(4),
                            payload = rs.getString(5),
                        ),
                    )
                }
            }
        }

    private fun applyPendingUpdate(
        conn: Connection,
        update: PendingUpdateRow,
    ) {
        val path = pathCodec.decode(update.prefixId, update.filename)
        if (!SourceIndexFilePolicy.isEligible(path)) {
            deleteFileRowsInTransaction(conn, update.prefixId, update.filename)
            return
        }
        when (update.op) {
            "upsert_file" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingFilePayload.serializer(),
                    requireNotNull(update.payload)
                )
                val fileUpdate = FileIndexUpdate(
                    path = path,
                    identifiers = payload.identifiers.toSet(),
                    packageName = payload.packageName,
                    modulePath = payload.modulePath,
                    sourceSet = payload.sourceSet,
                    imports = payload.imports.toSet(),
                    wildcardImports = payload.wildcardImports.toSet(),
                )
                internFqNamesInTransaction(conn, fqNamesFor(fileUpdate))
                insertFileDataInTransaction(conn, fileUpdate)
            }

            "remove_file" -> deleteFileRowsInTransaction(conn, update.prefixId, update.filename)
            "upsert_ref" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingReferencePayload.serializer(),
                    requireNotNull(update.payload)
                )
                val targetPath = payload.targetPath?.let(::normalizePendingPayloadPath)
                    ?.takeIf(SourceIndexFilePolicy::isEligible)
                internPathsInTransaction(conn, listOfNotNull(path, targetPath))
                internFqNamesInTransaction(conn, setOf(payload.targetFqName))
                upsertSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePath = path,
                    sourceOffset = payload.sourceOffset,
                    sourceFqName = payload.sourceFqName,
                    targetFqName = payload.targetFqName,
                    targetPath = targetPath,
                    targetOffset = targetPath?.let { payload.targetOffset },
                    edgeKind = payload.edgeKind,
                )
            }

            "remove_ref" -> {
                val payload = defaultCacheJson.decodeFromString(
                    PendingRemoveReferencePayload.serializer(),
                    requireNotNull(update.payload)
                )
                removeSymbolReferenceInTransaction(
                    conn = conn,
                    sourcePrefixId = update.prefixId,
                    sourceFilename = update.filename,
                    sourceOffset = payload.sourceOffset,
                    targetFqName = payload.targetFqName,
                )
            }

            else -> error("Unsupported pending update operation: ${update.op}")
        }
    }

    private fun removeSymbolReferenceInTransaction(
        conn: Connection,
        sourcePrefixId: Int,
        sourceFilename: String,
        sourceOffset: Int,
        targetFqName: String,
    ) {
        val targetFqId = fqCodec.idFor(targetFqName) ?: return
        conn.prepareStatement(
            """DELETE FROM symbol_references
               WHERE src_prefix_id = ?
                 AND src_filename = ?
                 AND source_offset = ?
                 AND target_fq_id = ?""",
        ).use { stmt ->
            stmt.setInt(1, sourcePrefixId)
            stmt.setString(2, sourceFilename)
            stmt.setInt(3, sourceOffset)
            stmt.setInt(4, targetFqId)
            stmt.executeUpdate()
        }
    }

    private fun clearDeclarationsFromFileInTransaction(
        conn: Connection,
        filePath: String,
    ) {
        loadInterningTables(conn)
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

    private fun insertDeclarationInTransaction(
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

    private fun normalizePendingPayloadPath(path: String): String {
        val rawPath = Path.of(path)
        return if (rawPath.isAbsolute) {
            rawPath.normalize().toString()
        } else {
            workspaceRoot.resolve(rawPath).normalize().toString()
        }
    }

    private fun markPendingUpdatesApplied(
        conn: Connection,
        updates: List<PendingUpdateRow>,
    ) {
        if (updates.isEmpty()) return
        conn.prepareStatement(
            """UPDATE pending_updates
               SET applied = 1
               WHERE applied = 0 AND prefix_id = ? AND filename = ?""",
        ).use { stmt ->
            updates.forEach { update ->
                stmt.setInt(1, update.prefixId)
                stmt.setString(2, update.filename)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun cleanupAppliedPendingUpdates(conn: Connection) {
        val retentionStartMs = System.currentTimeMillis() - PENDING_UPDATE_RETENTION_MS
        conn.prepareStatement("DELETE FROM pending_updates WHERE applied = 1 AND epoch_ms < ?").use { stmt ->
            stmt.setLong(1, retentionStartMs)
            stmt.executeUpdate()
        }
    }

    private fun decodeNullablePath(
        rs: java.sql.ResultSet,
        prefixColumn: Int,
        filenameColumn: Int,
    ): String? {
        val prefixId = rs.getNullableInt(prefixColumn) ?: return null
        val filename = requireNotNull(rs.getString(filenameColumn)) {
            "Path filename is missing for prefix_id=$prefixId"
        }
        return pathCodec.decode(prefixId, filename)
    }

    private fun java.sql.ResultSet.getNullableInt(column: Int): Int? =
        getObject(column)?.let { (it as Number).toInt() }

    private fun removeIneligibleSourceIndexRows(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """DELETE FROM symbol_references
                   WHERE src_filename NOT GLOB '*.kt'""",
            )
            stmt.execute(
                """UPDATE symbol_references
                   SET tgt_prefix_id = NULL,
                       tgt_filename = NULL,
                       target_offset = NULL
                   WHERE tgt_filename IS NOT NULL
                     AND tgt_filename NOT GLOB '*.kt'""",
            )
            for (table in listOf(
                "declarations",
                "identifier_paths",
                "file_gradle_source_sets",
                "file_gradle_projects",
                "file_metadata",
                "file_imports",
                "file_wildcard_imports",
                "file_manifest",
                "pending_updates",
            )) {
                stmt.execute("DELETE FROM $table WHERE filename NOT GLOB '*.kt'")
            }
        }
    }

    private data class PendingUpdateRow(
        val seq: Long,
        val op: String,
        val prefixId: Int,
        val filename: String,
        val payload: String?,
    )

    @Serializable
    private data class PendingFilePayload(
        val identifiers: List<String> = emptyList(),
        val packageName: String? = null,
        val modulePath: String? = null,
        val sourceSet: String? = null,
        val imports: List<String> = emptyList(),
        val wildcardImports: List<String> = emptyList(),
    )

    @Serializable
    private data class PendingReferencePayload(
        val sourceOffset: Int,
        val sourceFqName: String? = null,
        val targetFqName: String,
        val targetPath: String? = null,
        val targetOffset: Int? = null,
        val edgeKind: EdgeKind = EdgeKind.UNKNOWN,
    )

    @Serializable
    private data class PendingRemoveReferencePayload(
        val sourceOffset: Int,
        val targetFqName: String,
    )

    private fun normalizedSourceRoots(sourceRoots: Collection<Path>): List<Path> =
        sourceRoots
            .map { root -> root.toAbsolutePath().normalize() }
            .distinct()
            .sorted()

    private fun sourceRootDirKey(root: Path): String =
        pathCodec.decompose(root.resolve(sourceRootProbeFileName).toString()).first

    private fun dirIsWithinSourceRoot(
        dir: String,
        sourceRootDir: String,
    ): Boolean = when {
        sourceRootDir.isEmpty() -> !dir.startsWith(absolutePathPrefix)
        dir == sourceRootDir -> true
        else -> dir.startsWith("$sourceRootDir/")
    }

    private fun sourceRootFilesSql(
        sourceRootDir: String,
        limitPerRoot: Int?,
    ): String {
        val rootClause = if (sourceRootDir.isEmpty()) {
            "prefixes.dir_path NOT LIKE ?"
        } else {
            "(prefixes.dir_path = ? OR prefixes.dir_path LIKE ?)"
        }
        val limitClause = if (limitPerRoot == null) "" else " LIMIT ?"
        return """SELECT manifest.prefix_id, manifest.filename
                  FROM file_manifest manifest
                  JOIN path_prefixes prefixes ON prefixes.prefix_id = manifest.prefix_id
                  WHERE $rootClause
                  ORDER BY prefixes.dir_path, manifest.filename$limitClause"""
    }

    private fun bindSourceRootPrefix(
        stmt: java.sql.PreparedStatement,
        sourceRootDir: String,
    ) {
        if (sourceRootDir.isEmpty()) {
            stmt.setString(1, "$absolutePathPrefix%")
        } else {
            stmt.setString(1, sourceRootDir)
            stmt.setString(2, "$sourceRootDir/%")
        }
    }

    private companion object {
        const val PENDING_UPDATE_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        const val absolutePathPrefix = "__kast_abs__/"
        const val sourceRootProbeFileName = ".kast-source-root-probe.kt"
    }
}
