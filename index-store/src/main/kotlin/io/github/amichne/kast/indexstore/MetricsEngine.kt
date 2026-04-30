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

    /**
     * Fuzzy search the source index for symbol fully-qualified names that match [query].
     *
     * The matcher is case-insensitive and substring-based against `fq_names.fq_name`. Results
     * are ordered so that exact matches and short, simple-name matches rank first; remaining
     * matches are returned alphabetically. An empty or blank [query] returns the most-frequently
     * referenced symbols up to [limit].
     *
     * Returns an empty list when the workspace has not been indexed yet or the schema is stale,
     * mirroring the safe defaults used by the metrics queries.
     */
    fun searchSymbols(query: String, limit: Int = 25): List<String> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()
        val trimmed = query.trim()
        return readMetric(emptyList()) { conn ->
            val sql = if (trimmed.isEmpty()) {
                """
                SELECT names.fq_name
                FROM fq_names names
                JOIN symbol_references refs ON refs.target_fq_id = names.fq_id
                GROUP BY names.fq_id
                ORDER BY COUNT(*) DESC, names.fq_name ASC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT names.fq_name
                FROM fq_names names
                WHERE LOWER(names.fq_name) LIKE ? ESCAPE '\'
                ORDER BY
                    CASE
                        WHEN LOWER(names.fq_name) = ? THEN 0
                        WHEN LOWER(names.fq_name) LIKE ? ESCAPE '\' THEN 1
                        WHEN LOWER(names.fq_name) LIKE ? ESCAPE '\' THEN 2
                        ELSE 3
                    END,
                    LENGTH(names.fq_name) ASC,
                    names.fq_name ASC
                LIMIT ?
                """.trimIndent()
            }
            conn.prepareStatement(sql).use { stmt ->
                if (trimmed.isEmpty()) {
                    stmt.setInt(1, limit)
                } else {
                    val needle = trimmed.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    stmt.setString(1, "%$needle%")
                    stmt.setString(2, trimmed.lowercase())
                    stmt.setString(3, "%.$needle")
                    stmt.setString(4, "$needle%")
                    stmt.setInt(5, limit)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString(1))
                        }
                    }
                }
            }
        }
    }

    fun graph(fqName: String, depth: Int): MetricsGraph {
        require(depth >= 0) { "depth must be non-negative" }

        val focal = fanInMetric(fqName)
        val impact = changeImpactRadius(fqName = fqName, depth = depth)
        val directReferences = impact.filter { it.depth == 1 && it.viaTargetFqName == fqName }
        val childIdsByParent = buildChildIdsByParent(focal, impact)
        val impactBySourcePath = impact.groupBy { it.sourcePath }
        val nodes = buildList {
            add(focalSymbolNode(fqName, focal, directReferences, childIdsByParent))
            focal?.targetPath?.let { targetPath ->
                add(targetFileNode(targetPath, focal, childIdsByParent))
            }
            impactBySourcePath.forEach { (_, nodesForPath) ->
                val representative = nodesForPath.minBy { it.depth }
                add(sourceFileNode(nodesForPath, childIdsByParent, parentIdFor(representative, impact, fqName)))
                nodesForPath.forEach { node -> add(referenceEdgeNode(node)) }
            }
        }
        val edges = buildList {
            focal?.targetPath?.let { targetPath ->
                add(
                    MetricsGraphEdge(
                        from = fileNodeId(targetPath),
                        to = symbolNodeId(fqName),
                        edgeType = MetricsGraphEdgeType.CONTAINS,
                    ),
                )
            }
            impactBySourcePath.forEach { (_, nodesForPath) ->
                val representative = nodesForPath.minBy { it.depth }
                add(
                    MetricsGraphEdge(
                        from = parentIdFor(representative, impact, fqName),
                        to = sourceFileNodeId(representative.sourcePath),
                        edgeType = MetricsGraphEdgeType.REFERENCED_BY,
                        weight = nodesForPath.sumOf(ChangeImpactNode::occurrenceCount),
                    ),
                )
                nodesForPath.forEach { node ->
                    add(
                        MetricsGraphEdge(
                            from = sourceFileNodeId(node.sourcePath),
                            to = referenceEdgeNodeId(node),
                            edgeType = MetricsGraphEdgeType.REFERENCES,
                            weight = node.occurrenceCount,
                        ),
                    )
                }
            }
        }
        return MetricsGraph(
            focalNodeId = symbolNodeId(fqName),
            nodes = nodes,
            edges = edges,
            index = MetricsGraphIndex(
                symbolCount = 1 + impact.map(ChangeImpactNode::viaTargetFqName).filterNot { it == fqName }.distinct().size,
                fileCount = listOfNotNull(focal?.targetPath).plus(impact.map(ChangeImpactNode::sourcePath)).distinct().size,
                referenceCount = impact.sumOf(ChangeImpactNode::occurrenceCount),
                maxDepth = impact.maxOfOrNull(ChangeImpactNode::depth) ?: 0,
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

    private fun fanInMetric(fqName: String): FanInMetric? =
        readMetric(null) { conn ->
            conn.prepareStatement(
                """
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
                    WHERE target_name.fq_name = ?
                    GROUP BY refs.target_fq_id, refs.tgt_prefix_id, refs.tgt_filename, target_meta.module_path, target_meta.source_set
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, fqName)
                stmt.executeQuery().use { rs ->
                    val row = MetricResultRow(resultSet = rs, fields = FanInField.entries)
                    if (!rs.next()) {
                        null
                    } else {
                        FanInMetric(
                            targetFqName = row.string(FanInField.TARGET_FQ_NAME),
                            targetPath = row.nullablePath(FanInField.TARGET_DIR, FanInField.TARGET_FILENAME),
                            targetModulePath = row.nullableString(FanInField.TARGET_MODULE_PATH),
                            targetSourceSet = row.nullableString(FanInField.TARGET_SOURCE_SET),
                            occurrenceCount = row.int(FanInField.OCCURRENCE_COUNT),
                            sourceFileCount = row.int(FanInField.SOURCE_FILE_COUNT),
                            sourceModuleCount = row.int(FanInField.SOURCE_MODULE_COUNT),
                        )
                    }
                }
            }
        }

    private fun buildChildIdsByParent(
        focal: FanInMetric?,
        impact: List<ChangeImpactNode>,
    ): Map<String, List<String>> =
        buildMap {
            focal?.targetPath?.let { targetPath ->
                put(fileNodeId(targetPath), listOf(symbolNodeId(focal.targetFqName)))
            }
            impact.groupBy { parentIdFor(it, impact, focal?.targetFqName ?: it.viaTargetFqName) }
                .forEach { (parentId, children) ->
                    put(parentId, children.map { sourceFileNodeId(it.sourcePath) }.distinct())
                }
            // Append reference-edge children to existing source-file entries; do not overwrite the
            // source-file → source-file links established above.
            impact.forEach { node ->
                val parentId = sourceFileNodeId(node.sourcePath)
                put(parentId, getOrDefault(parentId, emptyList()) + referenceEdgeNodeId(node))
            }
        }

    private fun focalSymbolNode(
        fqName: String,
        focal: FanInMetric?,
        directReferences: List<ChangeImpactNode>,
        childIdsByParent: Map<String, List<String>>,
    ): MetricsGraphNode {
        val attributes = buildList {
            focal?.targetPath?.let { add("path=$it") }
            focal?.targetModulePath?.let { add("module=$it") }
            focal?.targetSourceSet?.let { add("sourceSet=$it") }
            add("incomingReferences=${focal?.occurrenceCount ?: directReferences.sumOf(ChangeImpactNode::occurrenceCount)}")
            add("sourceFiles=${focal?.sourceFileCount ?: directReferences.map(ChangeImpactNode::sourcePath).distinct().size}")
            focal?.sourceModuleCount?.let { add("sourceModules=$it") }
        }
        return MetricsGraphNode(
            id = symbolNodeId(fqName),
            name = fqName,
            type = MetricsGraphNodeType.SYMBOL,
            parentId = focal?.targetPath?.let(::fileNodeId),
            children = childIdsByParent[symbolNodeId(fqName)].orEmpty(),
            attributes = attributes,
        )
    }

    private fun targetFileNode(
        targetPath: String,
        focal: FanInMetric,
        childIdsByParent: Map<String, List<String>>,
    ): MetricsGraphNode =
        MetricsGraphNode(
            id = fileNodeId(targetPath),
            name = targetPath,
            type = MetricsGraphNodeType.FILE,
            children = childIdsByParent[fileNodeId(targetPath)].orEmpty(),
            attributes = listOfNotNull(
                "role=target",
                focal.targetModulePath?.let { "module=$it" },
                focal.targetSourceSet?.let { "sourceSet=$it" },
            ),
        )

    private fun sourceFileNode(
        nodes: List<ChangeImpactNode>,
        childIdsByParent: Map<String, List<String>>,
        parentId: String,
    ): MetricsGraphNode {
        val representative = nodes.minBy { it.depth }
        return MetricsGraphNode(
            id = sourceFileNodeId(representative.sourcePath),
            name = representative.sourcePath,
            type = MetricsGraphNodeType.FILE,
            parentId = parentId,
            children = childIdsByParent[sourceFileNodeId(representative.sourcePath)].orEmpty(),
            attributes = listOf(
                "incomingDepth=${representative.depth}",
                "references=${nodes.sumOf(ChangeImpactNode::occurrenceCount)}",
                "via=${representative.viaTargetFqName}",
            ),
        )
    }

    private fun referenceEdgeNode(node: ChangeImpactNode): MetricsGraphNode =
        MetricsGraphNode(
            id = referenceEdgeNodeId(node),
            name = node.viaTargetFqName,
            type = MetricsGraphNodeType.REFERENCE_EDGE,
            parentId = sourceFileNodeId(node.sourcePath),
            attributes = listOf(
                "from=${node.sourcePath}",
                "to=${node.viaTargetFqName}",
                "references=${node.occurrenceCount}",
            ),
        )

    private fun parentIdFor(
        node: ChangeImpactNode,
        impact: List<ChangeImpactNode>,
        fqName: String,
    ): String =
        impact
            .firstOrNull { candidate ->
                // Match the candidate's filename against the simple (last-segment) name of the via FQ
                // name to avoid false positives where the FQ name merely *ends with* the filename
                // (e.g. "com.example.CB" should not match a file "B.kt").
                candidate.depth == node.depth - 1 &&
                    node.viaTargetFqName.substringAfterLast('.') ==
                    candidate.sourcePath.substringAfterLast('/').removeSuffix(".kt")
            }
            ?.sourcePath
            ?.let(::sourceFileNodeId)
            ?: symbolNodeId(fqName)

    private fun symbolNodeId(fqName: String): String = "symbol:$fqName"

    private fun fileNodeId(path: String): String = "file:$path"

    private fun sourceFileNodeId(path: String): String = "source-file:$path"

    private fun referenceEdgeNodeId(node: ChangeImpactNode): String = "via:${node.viaTargetFqName}:${node.sourcePath}"

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
