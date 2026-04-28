package io.github.amichne.kast.indexstore

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

class MetricsEngine(workspaceRoot: Path) : AutoCloseable {
    private val dbPath: Path = sourceIndexDatabasePath(workspaceRoot)
    private val codec = PathInterningCodec(workspaceRoot)

    @Volatile
    private var cachedConnection: Connection? = null

    fun fanInRanking(limit: Int): List<FanInMetric> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()

        return readMetricRows(
            MetricQuerySpec(
                sql = """
                    SELECT target_name.fq_name,
                           target_prefix.dir_path,
                           refs.tgt_filename,
                           target_meta.module_path,
                           target_meta.source_set,
                           COUNT(*) AS occurrence_count,
                           COUNT(DISTINCT refs.src_prefix_id || ':' || refs.src_filename) AS source_file_count,
                           COUNT(DISTINCT source_meta.module_path) AS source_module_count
                    FROM symbol_references refs
                    LEFT JOIN file_metadata source_meta
                      ON source_meta.prefix_id = refs.src_prefix_id
                     AND source_meta.filename = refs.src_filename
                    LEFT JOIN file_metadata target_meta
                      ON target_meta.prefix_id = refs.tgt_prefix_id
                     AND target_meta.filename = refs.tgt_filename
                    JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                    LEFT JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                    GROUP BY refs.target_fq_id, refs.tgt_prefix_id, refs.tgt_filename, target_meta.module_path, target_meta.source_set
                     ORDER BY occurrence_count DESC,
                              target_name.fq_name ASC,
                              COALESCE(target_prefix.dir_path || '/' || refs.tgt_filename, '') ASC
                    LIMIT ?
                """.trimIndent(),
                fields = FanInField.entries,
                bind = { setInt(1, limit) },
                mapRow = {
                    FanInMetric(
                        targetFqName = string(FanInField.TARGET_FQ_NAME),
                        targetPath = nullablePath(FanInField.TARGET_DIR, FanInField.TARGET_FILENAME),
                        targetModulePath = nullableString(FanInField.TARGET_MODULE_PATH),
                        targetSourceSet = nullableString(FanInField.TARGET_SOURCE_SET),
                        occurrenceCount = int(FanInField.OCCURRENCE_COUNT),
                        sourceFileCount = int(FanInField.SOURCE_FILE_COUNT),
                        sourceModuleCount = int(FanInField.SOURCE_MODULE_COUNT),
                    )
                },
            ),
        )

    }

    fun fanOutRanking(limit: Int): List<FanOutMetric> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()

        return readMetricRows(
            MetricQuerySpec(
                sql = """
                    SELECT source_prefix.dir_path,
                           refs.src_filename,
                           source_meta.module_path,
                           source_meta.source_set,
                           COUNT(*) AS occurrence_count,
                           COUNT(DISTINCT refs.target_fq_id) AS target_symbol_count,
                           COUNT(DISTINCT CASE
                               WHEN refs.tgt_prefix_id IS NULL THEN NULL
                               ELSE refs.tgt_prefix_id || ':' || refs.tgt_filename
                           END) AS target_file_count,
                           COUNT(DISTINCT target_meta.module_path) AS target_module_count,
                           SUM(CASE WHEN refs.tgt_prefix_id IS NULL OR target_meta.prefix_id IS NULL THEN 1 ELSE 0 END)
                                AS external_target_count
                    FROM symbol_references refs
                    JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                    LEFT JOIN file_metadata source_meta
                      ON source_meta.prefix_id = refs.src_prefix_id
                     AND source_meta.filename = refs.src_filename
                    LEFT JOIN file_metadata target_meta
                      ON target_meta.prefix_id = refs.tgt_prefix_id
                     AND target_meta.filename = refs.tgt_filename
                    GROUP BY refs.src_prefix_id, refs.src_filename, source_meta.module_path, source_meta.source_set
                    ORDER BY occurrence_count DESC,
                             source_prefix.dir_path ASC,
                             refs.src_filename ASC
                    LIMIT ?
                """.trimIndent(),
                fields = FanOutField.entries,
                bind = { setInt(1, limit) },
                mapRow = {
                    FanOutMetric(
                        sourcePath = path(FanOutField.SOURCE_DIR, FanOutField.SOURCE_FILENAME),
                        sourceModulePath = nullableString(FanOutField.SOURCE_MODULE_PATH),
                        sourceSourceSet = nullableString(FanOutField.SOURCE_SOURCE_SET),
                        occurrenceCount = int(FanOutField.OCCURRENCE_COUNT),
                        targetSymbolCount = int(FanOutField.TARGET_SYMBOL_COUNT),
                        targetFileCount = int(FanOutField.TARGET_FILE_COUNT),
                        targetModuleCount = int(FanOutField.TARGET_MODULE_COUNT),
                        externalTargetCount = int(FanOutField.EXTERNAL_TARGET_COUNT),
                    )
                },
            ),
        )
    }

    fun moduleCouplingMatrix(): List<ModuleCouplingMetric> =
        readMetricRows(
            MetricQuerySpec(
                sql = """
                    SELECT source_meta.module_path, source_meta.source_set,
                           target_meta.module_path, target_meta.source_set,
                           COUNT(*) AS reference_count
                    FROM symbol_references refs
                    JOIN file_metadata source_meta
                      ON source_meta.prefix_id = refs.src_prefix_id
                     AND source_meta.filename = refs.src_filename
                    JOIN file_metadata target_meta
                      ON target_meta.prefix_id = refs.tgt_prefix_id
                     AND target_meta.filename = refs.tgt_filename
                    WHERE source_meta.module_path IS NOT NULL
                      AND target_meta.module_path IS NOT NULL
                      AND source_meta.module_path <> target_meta.module_path
                    GROUP BY source_meta.module_path, source_meta.source_set, target_meta.module_path, target_meta.source_set
                    ORDER BY reference_count DESC, source_meta.module_path ASC, target_meta.module_path ASC
                """.trimIndent(),
                fields = ModuleCouplingField.entries,
                mapRow = {
                    ModuleCouplingMetric(
                        sourceModulePath = string(ModuleCouplingField.SOURCE_MODULE_PATH),
                        sourceSourceSet = nullableString(ModuleCouplingField.SOURCE_SOURCE_SET),
                        targetModulePath = string(ModuleCouplingField.TARGET_MODULE_PATH),
                        targetSourceSet = nullableString(ModuleCouplingField.TARGET_SOURCE_SET),
                        referenceCount = int(ModuleCouplingField.REFERENCE_COUNT),
                    )
                },
            ),
        )

    fun deadCodeCandidates(): List<DeadCodeCandidate> =
        readMetricRows(
            MetricQuerySpec(
                sql = """
                    SELECT identifiers.identifier,
                           identifier_prefix.dir_path,
                           identifiers.filename,
                           metadata.module_path,
                           metadata.source_set,
                           package_name.fq_name
                    FROM identifier_paths identifiers
                    JOIN path_prefixes identifier_prefix ON identifier_prefix.prefix_id = identifiers.prefix_id
                     LEFT JOIN file_metadata metadata
                       ON metadata.prefix_id = identifiers.prefix_id
                      AND metadata.filename = identifiers.filename
                     LEFT JOIN fq_names package_name ON package_name.fq_id = metadata.package_fq_id
                     WHERE NOT EXISTS (
                         SELECT 1
                         FROM symbol_references refs
                         JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                         WHERE refs.tgt_prefix_id = identifiers.prefix_id
                           AND refs.tgt_filename = identifiers.filename
                           AND (
                               target_name.fq_name = identifiers.identifier
                               OR target_name.fq_name LIKE '%.' || identifiers.identifier
                           )
                    )
                    ORDER BY COALESCE(metadata.module_path, '') ASC,
                             identifier_prefix.dir_path ASC,
                             identifiers.filename ASC,
                             identifiers.identifier ASC
                """.trimIndent(),
                fields = DeadCodeField.entries,
                mapRow = {
                    DeadCodeCandidate(
                        identifier = string(DeadCodeField.IDENTIFIER),
                        path = path(DeadCodeField.DIR, DeadCodeField.FILENAME),
                        modulePath = nullableString(DeadCodeField.MODULE_PATH),
                        sourceSet = nullableString(DeadCodeField.SOURCE_SET),
                        packageName = nullableString(DeadCodeField.PACKAGE_NAME),
                        confidence = MetricsConfidence.LOW,
                        reason = "Identifier has no inbound reference rows matching its file and simple name; identifier_paths is lexical, not declaration-only.",
                    )
                },
            ),
        )


    fun changeImpactRadius(fqName: String, depth: Int): List<ChangeImpactNode> {
        require(depth >= 0) { "depth must be non-negative" }
        if (depth == 0) return emptyList()
        return readMetricRows(
            MetricQuerySpec(
                sql = """
                    WITH RECURSIVE impacted_files(depth, src_prefix_id, src_filename, via_target_fq_id) AS (
                        SELECT 1, src_prefix_id, src_filename, target_fq_id
                        FROM symbol_references
                        WHERE target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                        UNION
                        SELECT impacted_files.depth + 1,
                               refs.src_prefix_id,
                               refs.src_filename,
                               refs.target_fq_id
                        FROM impacted_files
                        JOIN symbol_references refs
                          ON refs.tgt_prefix_id = impacted_files.src_prefix_id
                         AND refs.tgt_filename = impacted_files.src_filename
                        WHERE impacted_files.depth < ?
                    ),
                    first_hits AS (
                        SELECT src_prefix_id, src_filename, MIN(depth) AS depth
                        FROM impacted_files
                        GROUP BY src_prefix_id, src_filename
                    )
                    SELECT source_prefix.dir_path,
                           first_hits.src_filename,
                           first_hits.depth,
                           via_target_name.fq_name,
                           COUNT(refs.source_offset) AS reference_count
                    FROM first_hits
                    JOIN impacted_files
                      ON impacted_files.src_prefix_id = first_hits.src_prefix_id
                     AND impacted_files.src_filename = first_hits.src_filename
                     AND impacted_files.depth = first_hits.depth
                     JOIN symbol_references refs
                       ON refs.src_prefix_id = impacted_files.src_prefix_id
                      AND refs.src_filename = impacted_files.src_filename
                      AND refs.target_fq_id = impacted_files.via_target_fq_id
                    JOIN fq_names via_target_name ON via_target_name.fq_id = impacted_files.via_target_fq_id
                     JOIN path_prefixes source_prefix ON source_prefix.prefix_id = first_hits.src_prefix_id
                    GROUP BY first_hits.src_prefix_id,
                             first_hits.src_filename,
                             first_hits.depth,
                              impacted_files.via_target_fq_id,
                              via_target_name.fq_name
                    ORDER BY first_hits.depth ASC,
                              reference_count DESC,
                              source_prefix.dir_path ASC,
                              first_hits.src_filename ASC,
                              via_target_name.fq_name ASC
                """.trimIndent(),
                fields = ChangeImpactField.entries,
                bind = {
                    setString(1, fqName)
                    setInt(2, depth)
                },
                mapRow = {
                    ChangeImpactNode(
                        sourcePath = path(ChangeImpactField.SOURCE_DIR, ChangeImpactField.SOURCE_FILENAME),
                        depth = int(ChangeImpactField.DEPTH),
                        viaTargetFqName = string(ChangeImpactField.VIA_TARGET_FQ_NAME),
                        occurrenceCount = int(ChangeImpactField.OCCURRENCE_COUNT),
                        semantics = ImpactSemantics.FILE_LEVEL_APPROXIMATION,
                    )
                },
            ),
        )
    }

    override fun close() {
        cachedConnection?.let { conn ->
            if (!conn.isClosed) conn.close()
        }
        cachedConnection = null
    }

    private inline fun <T> readMetric(defaultValue: T, query: (Connection) -> T): T {
        if (!Files.isRegularFile(dbPath)) return defaultValue
        val conn = connection()
        if (!schemaIsCurrent(conn)) return defaultValue
        return query(conn)
    }

    private fun <Field, Row> readMetricRows(spec: MetricQuerySpec<Field, Row>): List<Row> =
        readMetric(emptyList()) { conn ->
            conn.prepareStatement(spec.sql).use { stmt ->
                spec.bind(stmt)
                stmt.executeQuery().use { rs ->
                    val row = MetricResultRow(resultSet = rs, fields = spec.fields)
                    buildList {
                        while (rs.next()) {
                            add(spec.mapRow(row))
                        }
                    }
                }
            }
        }

    private fun schemaIsCurrent(conn: Connection): Boolean = try {
        val version = conn.prepareStatement("SELECT version FROM schema_version LIMIT 1").use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt(1) else null
        }
        version == SOURCE_INDEX_SCHEMA_VERSION && requiredTablesExist(conn)
    } catch (_: Exception) {
        false
    }

    private fun requiredTablesExist(conn: Connection): Boolean {
        val requiredTables = setOf(
            "path_prefixes",
            "fq_names",
            "symbol_references",
            "identifier_paths",
            "file_metadata"
        )
        val existingTables = conn.prepareStatement(
            """SELECT name FROM sqlite_master
               WHERE type = 'table' AND name IN (${requiredTables.joinToString(",") { "?" }})""",
        ).use { stmt ->
            requiredTables.forEachIndexed { index, tableName -> stmt.setString(index + 1, tableName) }
            val rs = stmt.executeQuery()
            buildSet {
                while (rs.next()) add(rs.getString(1))
            }
        }
        return existingTables == requiredTables
    }

    private fun connection(): Connection {
        cachedConnection?.let { conn ->
            if (!conn.isClosed) return conn
        }
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA busy_timeout=5000")
            stmt.execute("PRAGMA query_only=ON")
        }
        cachedConnection = conn
        return conn
    }

    private data class MetricQuerySpec<Field, Row>(
        val sql: String,
        val fields: List<Field>,
        val bind: PreparedStatement.() -> Unit = {},
        val mapRow: MetricResultRow<Field>.() -> Row,
    )

    private inner class MetricResultRow<Field>(
        private val resultSet: ResultSet,
        fields: List<Field>,
    ) {
        private val columnIndexes = fields.withIndex().associate { indexed -> indexed.value to indexed.index + 1 }

        fun string(field: Field): String = resultSet.getString(columnIndex(field))

        fun nullableString(field: Field): String? = resultSet.getString(columnIndex(field))

        fun int(field: Field): Int = resultSet.getInt(columnIndex(field))

        fun path(
            dirField: Field,
            filenameField: Field,
        ): String =
            checkNotNull(nullablePath(dirField, filenameField)) {
                "Metric row is missing a path for $dirField/$filenameField"
            }

        fun nullablePath(
            dirField: Field,
            filenameField: Field,
        ): String? {
            val filename = nullableString(filenameField) ?: return null
            val dir = checkNotNull(nullableString(dirField)) {
                "Metric row is missing a path prefix for $dirField/$filenameField"
            }
            return codec.compose(dir, filename)
        }

        private fun columnIndex(field: Field): Int = checkNotNull(columnIndexes[field]) {
            "Unknown metric field: $field"
        }
    }

    private enum class FanInField {
        TARGET_FQ_NAME,
        TARGET_DIR,
        TARGET_FILENAME,
        TARGET_MODULE_PATH,
        TARGET_SOURCE_SET,
        OCCURRENCE_COUNT,
        SOURCE_FILE_COUNT,
        SOURCE_MODULE_COUNT,
    }

    private enum class FanOutField {
        SOURCE_DIR,
        SOURCE_FILENAME,
        SOURCE_MODULE_PATH,
        SOURCE_SOURCE_SET,
        OCCURRENCE_COUNT,
        TARGET_SYMBOL_COUNT,
        TARGET_FILE_COUNT,
        TARGET_MODULE_COUNT,
        EXTERNAL_TARGET_COUNT,
    }

    private enum class ModuleCouplingField {
        SOURCE_MODULE_PATH,
        SOURCE_SOURCE_SET,
        TARGET_MODULE_PATH,
        TARGET_SOURCE_SET,
        REFERENCE_COUNT,
    }

    private enum class DeadCodeField {
        IDENTIFIER,
        DIR,
        FILENAME,
        MODULE_PATH,
        SOURCE_SET,
        PACKAGE_NAME,
    }

    private enum class ChangeImpactField {
        SOURCE_DIR,
        SOURCE_FILENAME,
        DEPTH,
        VIA_TARGET_FQ_NAME,
        OCCURRENCE_COUNT,
    }
}
