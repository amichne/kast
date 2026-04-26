package io.github.amichne.kast.indexstore

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class MetricsEngine(workspaceRoot: Path) : AutoCloseable {
    private val dbPath: Path = sourceIndexDatabasePath(workspaceRoot)

    @Volatile
    private var cachedConnection: Connection? = null

    fun fanInRanking(limit: Int): List<FanInMetric> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()
        return readMetric(emptyList()) { conn ->
            conn.prepareStatement(
                """SELECT refs.target_fq_name,
                          refs.target_path,
                          target_meta.module_name,
                          COUNT(*) AS occurrence_count,
                          COUNT(DISTINCT refs.source_path) AS source_file_count,
                          COUNT(DISTINCT source_meta.module_name) AS source_module_count
                   FROM symbol_references refs
                   LEFT JOIN file_metadata source_meta ON source_meta.path = refs.source_path
                   LEFT JOIN file_metadata target_meta ON target_meta.path = refs.target_path
                   GROUP BY refs.target_fq_name, refs.target_path, target_meta.module_name
                   ORDER BY occurrence_count DESC, refs.target_fq_name ASC, COALESCE(refs.target_path, '') ASC
                   LIMIT ?""",
            ).use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        add(
                            FanInMetric(
                                targetFqName = rs.getString(1),
                                targetPath = rs.getString(2),
                                targetModuleName = rs.getString(3),
                                occurrenceCount = rs.getInt(4),
                                sourceFileCount = rs.getInt(5),
                                sourceModuleCount = rs.getInt(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun fanOutRanking(limit: Int): List<FanOutMetric> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()
        return readMetric(emptyList()) { conn ->
            conn.prepareStatement(
                """SELECT refs.source_path,
                          source_meta.module_name,
                          COUNT(*) AS occurrence_count,
                          COUNT(DISTINCT refs.target_fq_name) AS target_symbol_count,
                          COUNT(DISTINCT refs.target_path) AS target_file_count,
                          COUNT(DISTINCT target_meta.module_name) AS target_module_count,
                          SUM(CASE WHEN refs.target_path IS NULL OR target_meta.path IS NULL THEN 1 ELSE 0 END)
                              AS external_target_count
                   FROM symbol_references refs
                   LEFT JOIN file_metadata source_meta ON source_meta.path = refs.source_path
                   LEFT JOIN file_metadata target_meta ON target_meta.path = refs.target_path
                   GROUP BY refs.source_path, source_meta.module_name
                   ORDER BY occurrence_count DESC, refs.source_path ASC
                   LIMIT ?""",
            ).use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        add(
                            FanOutMetric(
                                sourcePath = rs.getString(1),
                                sourceModuleName = rs.getString(2),
                                occurrenceCount = rs.getInt(3),
                                targetSymbolCount = rs.getInt(4),
                                targetFileCount = rs.getInt(5),
                                targetModuleCount = rs.getInt(6),
                                externalTargetCount = rs.getInt(7),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun moduleCouplingMatrix(): List<ModuleCouplingMetric> =
        readMetric(emptyList()) { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    """SELECT source_meta.module_name, target_meta.module_name, COUNT(*) AS reference_count
                       FROM symbol_references refs
                       JOIN file_metadata source_meta ON source_meta.path = refs.source_path
                       JOIN file_metadata target_meta ON target_meta.path = refs.target_path
                       WHERE source_meta.module_name IS NOT NULL
                         AND target_meta.module_name IS NOT NULL
                         AND source_meta.module_name <> target_meta.module_name
                       GROUP BY source_meta.module_name, target_meta.module_name
                       ORDER BY reference_count DESC, source_meta.module_name ASC, target_meta.module_name ASC""",
                )
                buildList {
                    while (rs.next()) {
                        add(
                            ModuleCouplingMetric(
                                sourceModuleName = rs.getString(1),
                                targetModuleName = rs.getString(2),
                                referenceCount = rs.getInt(3),
                            ),
                        )
                    }
                }
            }
        }

    fun deadCodeCandidates(): List<DeadCodeCandidate> =
        readMetric(emptyList()) { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    """SELECT identifiers.identifier, identifiers.path, metadata.module_name, metadata.package_name
                       FROM identifier_paths identifiers
                       LEFT JOIN file_metadata metadata ON metadata.path = identifiers.path
                       WHERE NOT EXISTS (
                           SELECT 1
                           FROM symbol_references refs
                           WHERE refs.target_path = identifiers.path
                             AND (
                                 refs.target_fq_name = identifiers.identifier
                                 OR refs.target_fq_name LIKE '%.' || identifiers.identifier
                             )
                       )
                       ORDER BY COALESCE(metadata.module_name, '') ASC,
                                identifiers.path ASC,
                                identifiers.identifier ASC""",
                )
                buildList {
                    while (rs.next()) {
                        add(
                            DeadCodeCandidate(
                                identifier = rs.getString(1),
                                path = rs.getString(2),
                                moduleName = rs.getString(3),
                                packageName = rs.getString(4),
                                confidence = MetricsConfidence.LOW,
                                reason = "Identifier has no inbound reference rows matching its file and simple name; identifier_paths is lexical, not declaration-only.",
                            ),
                        )
                    }
                }
            }
        }

    fun changeImpactRadius(fqName: String, depth: Int): List<ChangeImpactNode> {
        require(depth >= 0) { "depth must be non-negative" }
        if (depth == 0) return emptyList()
        return readMetric(emptyList()) { conn ->
            conn.prepareStatement(
                """WITH RECURSIVE impacted_files(depth, source_path, via_target_fq_name) AS (
                       SELECT 1, source_path, target_fq_name
                       FROM symbol_references
                       WHERE target_fq_name = ?
                       UNION
                       SELECT impacted_files.depth + 1, refs.source_path, refs.target_fq_name
                       FROM impacted_files
                       JOIN symbol_references refs ON refs.target_path = impacted_files.source_path
                       WHERE impacted_files.depth < ?
                   ),
                   first_hits AS (
                       SELECT source_path, MIN(depth) AS depth
                       FROM impacted_files
                       GROUP BY source_path
                   )
                   SELECT first_hits.source_path,
                          first_hits.depth,
                          impacted_files.via_target_fq_name,
                          COUNT(refs.source_offset) AS reference_count
                    FROM first_hits
                    JOIN impacted_files
                      ON impacted_files.source_path = first_hits.source_path
                     AND impacted_files.depth = first_hits.depth
                   JOIN symbol_references refs
                     ON refs.source_path = impacted_files.source_path
                    AND refs.target_fq_name = impacted_files.via_target_fq_name
                   GROUP BY first_hits.source_path, first_hits.depth, impacted_files.via_target_fq_name
                   ORDER BY first_hits.depth ASC,
                            reference_count DESC,
                            first_hits.source_path ASC,
                            impacted_files.via_target_fq_name ASC""",
            ).use { stmt ->
                stmt.setString(1, fqName)
                stmt.setInt(2, depth)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        add(
                            ChangeImpactNode(
                                sourcePath = rs.getString(1),
                                depth = rs.getInt(2),
                                viaTargetFqName = rs.getString(3),
                                occurrenceCount = rs.getInt(4),
                                semantics = ImpactSemantics.FILE_LEVEL_APPROXIMATION,
                            ),
                        )
                    }
                }
            }
        }
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
        val requiredTables = setOf("symbol_references", "identifier_paths", "file_metadata")
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
}
