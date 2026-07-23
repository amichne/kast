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
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolFlags
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKey
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbolKind
import io.github.amichne.kast.api.contract.result.SemanticGraphModality
import io.github.amichne.kast.api.contract.result.SemanticGraphOrigin
import io.github.amichne.kast.api.contract.result.SemanticGraphVisibility
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphFileIndexUpdate
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphIndexSnapshot
import io.github.amichne.kast.indexstore.api.graph.SemanticGraphWriteResult
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
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
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
    private val overlayManifest: OverlayManifest? = dbPath.resolveSibling(REPOSITORY_OVERLAY_FILE)
        .takeIf(Files::isRegularFile)
        ?.let { path -> Json.decodeFromString(Files.readString(path)) }
    private val repositoryBasePath: Path? = overlayManifest?.baseDatabase
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
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
                attachRepositoryBase(conn)
                if (readSchemaVersion(conn) == null) {
                    conn.autoCommit = false
                    createAllTables(conn)
                    conn.commit()
                    conn.autoCommit = true
                }
                initializeRepositoryOverlay(conn)
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

    private fun attachRepositoryBase(conn: Connection) {
        val base = repositoryBasePath ?: return
        check(Files.isRegularFile(base)) { "Repository snapshot base is unavailable: $base" }
        val uri = "${base.toUri().toASCIIString()}?mode=ro&immutable=1".replace("'", "''")
        conn.createStatement().use { statement ->
            statement.execute("ATTACH DATABASE '$uri' AS repository_base")
            val rows = statement.executeQuery("SELECT version FROM repository_base.schema_version LIMIT 1")
            check(rows.next() && rows.getInt(1) == SOURCE_INDEX_SCHEMA_VERSION) {
                "Repository snapshot base schema does not match $SOURCE_INDEX_SCHEMA_VERSION"
            }
        }
    }

    private fun initializeRepositoryOverlay(conn: Connection) {
        val manifest = overlayManifest ?: return
        conn.prepareStatement(
            "INSERT OR IGNORE INTO repository_overlay_tombstones(path) VALUES (?)",
        ).use { statement ->
            (manifest.tombstones + manifest.shards.keys).sorted().forEach { path ->
                statement.setString(1, path)
                statement.addBatch()
            }
            statement.executeBatch()
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
        conn.prepareStatement("SELECT version FROM main.schema_version LIMIT 1").use { stmt ->
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
            "semantic_files" to mapOf(
                "id" to false,
                "path" to true,
                "content_hash" to false,
                "refresh_status" to true,
                "diagnostics_json" to true,
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
        stmt.execute("DROP VIEW IF EXISTS semantic_module_quotient")
        stmt.execute("DROP VIEW IF EXISTS semantic_package_quotient")
        stmt.execute("DROP VIEW IF EXISTS semantic_file_quotient")
        stmt.execute("DROP TRIGGER IF EXISTS fq_names_ai")
        stmt.execute("DROP TRIGGER IF EXISTS fq_names_ad")
        stmt.execute("DROP TRIGGER IF EXISTS fq_names_au")
        stmt.execute("DROP TABLE IF EXISTS fq_names_fts")
        stmt.execute("DROP TABLE IF EXISTS pending_updates")
        stmt.execute("DROP TABLE IF EXISTS module_index_progress")
        stmt.execute("DROP TABLE IF EXISTS semantic_edge_occurrences")
        stmt.execute("DROP TABLE IF EXISTS semantic_symbol_annotations")
        stmt.execute("DROP TABLE IF EXISTS semantic_type_edges")
        stmt.execute("DROP TABLE IF EXISTS semantic_symbols")
        stmt.execute("DROP TABLE IF EXISTS semantic_types")
        stmt.execute("DROP TABLE IF EXISTS semantic_files")
        stmt.execute("DROP TABLE IF EXISTS repository_overlay_tombstones")
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
            """CREATE TABLE IF NOT EXISTS semantic_files (
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                package_name TEXT,
                module_name TEXT,
                content_hash TEXT,
                refresh_status TEXT NOT NULL CHECK(refresh_status IN ('REFRESHED','CACHED','REMOVED')),
                diagnostics_json TEXT NOT NULL
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS repository_overlay_tombstones (
                path TEXT PRIMARY KEY
            ) WITHOUT ROWID""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_types (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                classifier TEXT,
                nullability TEXT NOT NULL,
                debug_text TEXT NOT NULL,
                flexible_lower_id INTEGER,
                flexible_upper_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_symbols (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                file_id INTEGER NOT NULL,
                owner_id INTEGER,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                fq_name TEXT,
                signature TEXT,
                visibility TEXT NOT NULL DEFAULT 'PUBLIC',
                modality TEXT,
                origin TEXT NOT NULL DEFAULT 'SOURCE',
                is_expect INTEGER NOT NULL DEFAULT 0,
                is_actual INTEGER NOT NULL DEFAULT 0,
                is_override INTEGER NOT NULL DEFAULT 0,
                is_sealed INTEGER NOT NULL DEFAULT 0,
                is_delegated INTEGER NOT NULL DEFAULT 0,
                declared_type_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL,
                FOREIGN KEY(file_id) REFERENCES semantic_files(id) ON DELETE CASCADE,
                FOREIGN KEY(owner_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE,
                FOREIGN KEY(declared_type_id) REFERENCES semantic_types(id),
                FOREIGN KEY(receiver_type_id) REFERENCES semantic_types(id),
                FOREIGN KEY(return_type_id) REFERENCES semantic_types(id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_type_edges (
                id INTEGER PRIMARY KEY,
                parent_type_id INTEGER NOT NULL,
                child_type_id INTEGER,
                role TEXT NOT NULL,
                position INTEGER NOT NULL,
                variance TEXT NOT NULL,
                FOREIGN KEY(parent_type_id) REFERENCES semantic_types(id) ON DELETE CASCADE,
                FOREIGN KEY(child_type_id) REFERENCES semantic_types(id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_symbol_annotations (
                symbol_id INTEGER NOT NULL,
                annotation_name TEXT NOT NULL,
                PRIMARY KEY(symbol_id, annotation_name),
                FOREIGN KEY(symbol_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_edge_occurrences (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                source_file_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                context TEXT NOT NULL,
                resolved_target_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL,
                FOREIGN KEY(source_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE,
                FOREIGN KEY(target_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE,
                FOREIGN KEY(source_file_id) REFERENCES semantic_files(id) ON DELETE CASCADE,
                FOREIGN KEY(resolved_target_id) REFERENCES semantic_symbols(id) ON DELETE SET NULL
            )""",
        )

        stmt.execute(
            """CREATE VIEW semantic_file_quotient AS
               SELECT source.file_id AS source_container_id,
                      target.file_id AS target_container_id,
                      edges.kind AS kind,
                      edges.context AS context,
                      COUNT(*) AS weight
               FROM semantic_edge_occurrences edges
               JOIN semantic_symbols source ON source.id = edges.source_id
               JOIN semantic_symbols target ON target.id = edges.target_id
               GROUP BY source.file_id, target.file_id, edges.kind, edges.context""",
        )
        stmt.execute(
            """CREATE VIEW semantic_package_quotient AS
               SELECT source_file.package_name AS source_container,
                      target_file.package_name AS target_container,
                      edges.kind AS kind,
                      edges.context AS context,
                      COUNT(*) AS weight
               FROM semantic_edge_occurrences edges
               JOIN semantic_symbols source ON source.id = edges.source_id
               JOIN semantic_files source_file ON source_file.id = source.file_id
               JOIN semantic_symbols target ON target.id = edges.target_id
               JOIN semantic_files target_file ON target_file.id = target.file_id
               WHERE source_file.package_name IS NOT NULL AND target_file.package_name IS NOT NULL
               GROUP BY source_file.package_name, target_file.package_name, edges.kind, edges.context""",
        )
        stmt.execute(
            """CREATE VIEW semantic_module_quotient AS
               SELECT source_file.module_name AS source_container,
                      target_file.module_name AS target_container,
                      edges.kind AS kind,
                      edges.context AS context,
                      COUNT(*) AS weight
               FROM semantic_edge_occurrences edges
               JOIN semantic_symbols source ON source.id = edges.source_id
               JOIN semantic_files source_file ON source_file.id = source.file_id
               JOIN semantic_symbols target ON target.id = edges.target_id
               JOIN semantic_files target_file ON target_file.id = target.file_id
               WHERE source_file.module_name IS NOT NULL AND target_file.module_name IS NOT NULL
               GROUP BY source_file.module_name, target_file.module_name, edges.kind, edges.context""",
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
    ): SemanticGraphWriteResult {
        require(updates.isNotEmpty() || removedPaths.isNotEmpty()) {
            "Semantic graph replacement requires an updated or removed file"
        }
        synchronized(writeLock) {
            val conn = connection()
            conn.autoCommit = false
            return try {
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
                incrementGenerationInTransaction(conn)
                val generation = readGenerationInTransaction(conn)
                conn.commit()
                SemanticGraphWriteResult(
                    generation = generation,
                    fileCount = updates.size,
                    symbolCount = updates.sumOf { update -> update.symbols.size },
                    edgeOccurrenceCount = updates.sumOf { update -> update.relations.size },
                )
            } catch (failure: Exception) {
                conn.rollback()
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

    fun readSemanticGraph(filePaths: Collection<SemanticGraphSourcePath>): SemanticGraphIndexSnapshot {
        synchronized(writeLock) {
            val conn = connection()
            val generation = readGenerationInTransaction(conn)
            prepareSemanticGraphScope(conn, filePaths)
            val files = conn.prepareStatement(
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

    fun semanticGraphSymbolKeys(): Set<SemanticGraphSymbolKey> = synchronized(writeLock) {
        connection().prepareStatement(
            "SELECT stable_key FROM semantic_symbols ORDER BY stable_key",
        ).use { statement ->
            val rows = statement.executeQuery()
            buildSet {
                while (rows.next()) add(SemanticGraphSymbolKey.parse(rows.getString(1)))
            }
        }
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
        const val REPOSITORY_OVERLAY_FILE = "repository-overlay.json"
        const val PENDING_UPDATE_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        const val absolutePathPrefix = "__kast_abs__/"
        const val sourceRootProbeFileName = ".kast-source-root-probe.kt"
    }
}
