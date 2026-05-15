package io.github.amichne.kast.indexstore.metrics

import io.github.amichne.kast.indexstore.api.graph.MetricsGraph
import io.github.amichne.kast.indexstore.api.metrics.general.Confidence
import io.github.amichne.kast.indexstore.api.metrics.general.ConfidenceLevel
import io.github.amichne.kast.indexstore.api.metrics.general.DeclarationInfo
import io.github.amichne.kast.indexstore.api.metrics.general.FileFilterSpec
import io.github.amichne.kast.indexstore.api.metrics.general.SemanticBasis
import io.github.amichne.kast.indexstore.api.metrics.general.filterByPath
import io.github.amichne.kast.indexstore.api.metrics.impact.ChangeImpactNode
import io.github.amichne.kast.indexstore.api.metrics.impact.DeadCodeCandidate
import io.github.amichne.kast.indexstore.api.metrics.impact.FanInMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.FanOutMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.LowUsageSymbol
import io.github.amichne.kast.indexstore.api.metrics.impact.SymbolEdgeMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ApiSurfaceMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleBoundaryMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCouplingMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCycleMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleDepthDiagnosis
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleDepthMetric
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.graph.Edge
import io.github.amichne.kast.indexstore.graph.EdgeType
import io.github.amichne.kast.indexstore.graph.Graph
import io.github.amichne.kast.indexstore.graph.Index
import io.github.amichne.kast.indexstore.graph.Node
import io.github.amichne.kast.indexstore.graph.NodeType
import io.github.amichne.kast.indexstore.graph.toApi
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import io.github.amichne.kast.indexstore.store.codec.PathInterningCodec
import io.github.amichne.kast.indexstore.store.jdbc.SqliteJdbcDriverBootstrap
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class MetricsEngine(workspaceRoot: Path) : AutoCloseable {
    private val dbPath: Path = sourceIndexDatabasePath(workspaceRoot)
    private val codec = PathInterningCodec(workspaceRoot)

    @Volatile
    private var cachedConnection: Connection? = null

    fun declarations(filter: FileFilterSpec = FileFilterSpec()): List<DeclarationInfo> =
        readMetric(emptyList()) { conn ->
            conn.prepareStatement(
                """
                SELECT names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.module_path,
                       declarations.source_set
                FROM declarations
                JOIN fq_names names ON names.fq_id = declarations.fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                ORDER BY names.fq_name ASC, prefixes.dir_path ASC, declarations.filename ASC
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DeclarationInfo(
                                    fqName = rs.getString(1),
                                    kind = rs.getString(2),
                                    visibility = rs.getString(3),
                                    path = codec.compose(rs.getString(4), rs.getString(5)),
                                    modulePath = rs.getString(6),
                                    sourceSet = rs.getString(7),
                                ),
                            )
                        }
                    }
                }
            }.filterByPath(filter) { it.path }
        }

    fun apiSurface(modulePath: String? = null): List<ApiSurfaceMetric> =
        readMetric(emptyList()) { conn ->
            conn.prepareStatement(
                """
                SELECT module_path,
                       SUM(CASE WHEN visibility = 'PUBLIC' THEN 1 ELSE 0 END) AS public_count,
                       SUM(CASE WHEN visibility = 'INTERNAL' THEN 1 ELSE 0 END) AS internal_count,
                       SUM(CASE WHEN visibility IN ('PRIVATE', 'LOCAL') THEN 1 ELSE 0 END) AS private_count,
                       COUNT(*) AS total_count
                FROM declarations
                WHERE module_path IS NOT NULL
                  AND (? IS NULL OR module_path = ?)
                GROUP BY module_path
                ORDER BY module_path ASC
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, modulePath)
                stmt.setString(2, modulePath)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val privateCount = rs.getInt(4)
                            val totalCount = rs.getInt(5)
                            add(
                                ApiSurfaceMetric(
                                    modulePath = rs.getString(1),
                                    publicSymbolCount = rs.getInt(2),
                                    internalSymbolCount = rs.getInt(3),
                                    privateSymbolCount = privateCount,
                                    totalSymbolCount = totalCount,
                                    encapsulationRatio = ratio(privateCount, totalCount),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun symbolEdges(
        fqName: String? = null,
        edgeKinds: Set<EdgeKind> = emptySet(),
    ): List<SymbolEdgeMetric> =
        readMetric(emptyList()) { conn ->
            val kindFilter = edgeKinds.map(EdgeKind::name).toSet()
            conn.prepareStatement(
                """
                SELECT source_names.fq_name,
                       target_names.fq_name,
                       refs.edge_kind,
                       source_prefix.dir_path,
                       refs.src_filename,
                       target_prefix.dir_path,
                       refs.tgt_filename,
                       COUNT(*) AS reference_count
                FROM symbol_references refs
                LEFT JOIN fq_names source_names ON source_names.fq_id = refs.source_fq_id
                JOIN fq_names target_names ON target_names.fq_id = refs.target_fq_id
                JOIN path_prefixes source_prefix ON source_prefix.prefix_id = refs.src_prefix_id
                LEFT JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                WHERE (? IS NULL OR source_names.fq_name = ? OR target_names.fq_name = ?)
                GROUP BY refs.source_fq_id, refs.target_fq_id, refs.edge_kind,
                         refs.src_prefix_id, refs.src_filename, refs.tgt_prefix_id, refs.tgt_filename
                ORDER BY reference_count DESC, target_names.fq_name ASC, source_prefix.dir_path ASC, refs.src_filename ASC
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, fqName)
                stmt.setString(2, fqName)
                stmt.setString(3, fqName)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val edgeKind = rs.getString(3)
                            if (kindFilter.isEmpty() || edgeKind in kindFilter) {
                                add(
                                    SymbolEdgeMetric(
                                        sourceFqName = rs.getString(1),
                                        targetFqName = rs.getString(2),
                                        edgeKind = edgeKind,
                                        sourcePath = codec.compose(rs.getString(4), rs.getString(5)),
                                        targetPath = nullablePath(rs, 6, 7),
                                        count = rs.getInt(8),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

    fun moduleBoundary(modulePath: String? = null): List<ModuleBoundaryMetric> =
        readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            val exported = exportedSymbolsByModule(conn)
            val consumed = consumedTargetsByModule(conn)
            val apiRefs = crossModuleReferencesByVisibility(conn, "PUBLIC")
            val internalLeaks = crossModuleReferencesByVisibility(conn, "INTERNAL")
            exported.keys
                .plus(consumed.keys)
                .filter { modulePath == null || it == modulePath }
                .sorted()
                .map { module ->
                    ModuleBoundaryMetric(
                        modulePath = module,
                        exportedSymbolCount = exported[module] ?: 0,
                        consumedSymbolCount = consumed[module] ?: 0,
                        publicApiReferences = apiRefs[module] ?: 0,
                        internalLeakReferences = internalLeaks[module] ?: 0,
                        confidence = confidence,
                    )
                }
        }

    fun fanInRanking(limit: Int, filter: FileFilterSpec = FileFilterSpec()): List<FanInMetric> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()
        return readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            val byEdgeKind = edgeBreakdownsByTarget(conn)
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
                GROUP BY refs.target_fq_id, refs.tgt_prefix_id, refs.tgt_filename, target_meta.module_path, target_meta.source_set
                ORDER BY occurrence_count DESC,
                         target_name.fq_name ASC,
                         COALESCE(target_prefix.dir_path || '/' || refs.tgt_filename, '') ASC
                LIMIT ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val targetFqName = rs.getString(1)
                            add(
                                FanInMetric(
                                    targetFqName = targetFqName,
                                    targetPath = nullablePath(rs, 2, 3),
                                    targetModulePath = rs.getString(4),
                                    targetSourceSet = rs.getString(5),
                                    occurrenceCount = rs.getInt(6),
                                    sourceFileCount = rs.getInt(7),
                                    sourceModuleCount = rs.getInt(8),
                                    byEdgeKind = byEdgeKind[targetFqName].orEmpty(),
                                    confidence = confidence,
                                ),
                            )
                        }
                    }
                }
            }.filterByPath(filter) { it.targetPath }
        }
    }

    fun fanOutRanking(limit: Int, filter: FileFilterSpec = FileFilterSpec()): List<FanOutMetric> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()
        return readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            val byEdgeKind = edgeBreakdownsBySource(conn)
            conn.prepareStatement(
                """
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
            ).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val sourcePath = codec.compose(rs.getString(1), rs.getString(2))
                            add(
                                FanOutMetric(
                                    sourcePath = sourcePath,
                                    sourceModulePath = rs.getString(3),
                                    sourceSourceSet = rs.getString(4),
                                    occurrenceCount = rs.getInt(5),
                                    targetSymbolCount = rs.getInt(6),
                                    targetFileCount = rs.getInt(7),
                                    targetModuleCount = rs.getInt(8),
                                    externalTargetCount = rs.getInt(9),
                                    byEdgeKind = byEdgeKind[sourcePath].orEmpty(),
                                    confidence = confidence,
                                ),
                            )
                        }
                    }
                }
            }.filterByPath(filter) { it.sourcePath }
        }
    }

    fun moduleCouplingMatrix(): List<ModuleCouplingMetric> =
        readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            val byEdgeKind = edgeBreakdownsByModulePair(conn)
            conn.prepareStatement(
                """
                SELECT source_meta.module_path, source_meta.source_set,
                       target_meta.module_path, target_meta.source_set,
                       COUNT(*) AS reference_count,
                       SUM(CASE WHEN declarations.visibility = 'PUBLIC' THEN 1 ELSE 0 END) AS public_api_count,
                       SUM(CASE WHEN declarations.visibility = 'INTERNAL' THEN 1 ELSE 0 END) AS internal_leak_count
                FROM symbol_references refs
                JOIN file_metadata source_meta
                  ON source_meta.prefix_id = refs.src_prefix_id
                 AND source_meta.filename = refs.src_filename
                JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                LEFT JOIN declarations ON declarations.fq_id = refs.target_fq_id
                WHERE source_meta.module_path IS NOT NULL
                  AND target_meta.module_path IS NOT NULL
                  AND source_meta.module_path <> target_meta.module_path
                GROUP BY source_meta.module_path, source_meta.source_set, target_meta.module_path, target_meta.source_set
                ORDER BY reference_count DESC, source_meta.module_path ASC, target_meta.module_path ASC
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val sourceModule = rs.getString(1)
                            val targetModule = rs.getString(3)
                            add(
                                ModuleCouplingMetric(
                                    sourceModulePath = sourceModule,
                                    sourceSourceSet = rs.getString(2),
                                    targetModulePath = targetModule,
                                    targetSourceSet = rs.getString(4),
                                    referenceCount = rs.getInt(5),
                                    publicApiCount = rs.getInt(6),
                                    internalLeakCount = rs.getInt(7),
                                    byEdgeKind = byEdgeKind[sourceModule to targetModule].orEmpty(),
                                    confidence = confidence,
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun lowUsageSymbols(maxOccurrences: Int = 2, limit: Int = 50, filter: FileFilterSpec = FileFilterSpec()): List<LowUsageSymbol> {
        require(maxOccurrences >= 0) { "maxOccurrences must be non-negative" }
        require(limit >= 0) { "limit must be non-negative" }
        if (maxOccurrences == 0 || limit == 0) return emptyList()
        return readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            conn.prepareStatement(
                """
                SELECT target_name.fq_name,
                       target_prefix.dir_path,
                       refs.tgt_filename,
                       target_meta.module_path,
                       COUNT(*) AS occurrence_count,
                       COUNT(DISTINCT refs.src_prefix_id || ':' || refs.src_filename) AS source_file_count
                FROM symbol_references refs
                JOIN fq_names target_name ON target_name.fq_id = refs.target_fq_id
                JOIN path_prefixes target_prefix ON target_prefix.prefix_id = refs.tgt_prefix_id
                JOIN file_metadata target_meta
                  ON target_meta.prefix_id = refs.tgt_prefix_id
                 AND target_meta.filename = refs.tgt_filename
                GROUP BY refs.target_fq_id, refs.tgt_prefix_id, refs.tgt_filename, target_meta.module_path
                HAVING source_file_count = 1
                   AND occurrence_count <= ?
                ORDER BY COALESCE(target_meta.module_path, '') ASC,
                         target_name.fq_name ASC
                LIMIT ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setInt(1, maxOccurrences)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LowUsageSymbol(
                                    targetFqName = rs.getString(1),
                                    targetPath = nullablePath(rs, 2, 3),
                                    targetModulePath = rs.getString(4),
                                    occurrenceCount = rs.getInt(5),
                                    sourceFileCount = rs.getInt(6),
                                    confidence = confidence,
                                ),
                            )
                        }
                    }
                }
            }.filterByPath(filter) { it.targetPath }
        }
    }

    fun moduleCycles(): List<ModuleCycleMetric> {
        val confidence = readMetric(SPECULATIVE_CONFIDENCE, ::currentConfidence)
        val edgeWeights = moduleCouplingMatrix()
            .fold(mutableMapOf<Pair<String, String>, Int>()) { weights, edge ->
                val key = edge.sourceModulePath to edge.targetModulePath
                weights[key] = weights.getOrDefault(key, 0) + edge.referenceCount
                weights
            }
        val adjacency = buildMap<String, List<String>> {
            val nodes = edgeWeights.keys.flatMap { (source, target) -> listOf(source, target) }.toSortedSet()
            nodes.forEach { node ->
                put(
                    node,
                    edgeWeights.keys
                        .filter { (source, _) -> source == node }
                        .map { (_, target) -> target }
                        .sorted(),
                )
            }
        }

        return stronglyConnectedComponents(adjacency)
            .filter { it.size > 1 }
            .mapNotNull { component ->
                shortestCycle(component, adjacency)?.let { cycle ->
                    val cycleEdges = cycle.zipWithNext()
                    val totalReferenceCount = cycleEdges.sumOf { (source, target) ->
                        checkNotNull(edgeWeights[source to target]) { "Missing module edge $source -> $target" }
                    }
                    val weakestEdge = cycleEdges.minWith(
                        compareBy<Pair<String, String>>(
                            { (source, target) -> checkNotNull(edgeWeights[source to target]) },
                            { it.first },
                            { it.second },
                        ),
                    )
                    ModuleCycleMetric(
                        cycle = cycle,
                        totalReferenceCount = totalReferenceCount,
                        weakestEdgeSource = weakestEdge.first,
                        weakestEdgeTarget = weakestEdge.second,
                        weakestEdgeReferenceCount = checkNotNull(edgeWeights[weakestEdge]),
                        confidence = confidence,
                    )
                }
            }
            .sortedBy { it.cycle.joinToString(" -> ") }
    }

    fun moduleDepthMetrics(): List<ModuleDepthMetric> =
        readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            val declarations = moduleDeclarationStats(conn)
            val references = moduleReferenceStats(conn)
            declarations.values
                .sortedBy { it.modulePath }
                .map { declaration ->
                    val reference = references[declaration.modulePath] ?: ModuleReferenceStats(
                        modulePath = declaration.modulePath,
                        internalRefCount = 0,
                        externalRefCount = 0,
                    )
                    val totalRefs = reference.internalRefCount + reference.externalRefCount
                    val cohesionRatio = ratio(reference.internalRefCount, totalRefs)
                    val refsPerFile = ratio(reference.internalRefCount, declaration.fileCount)
                    ModuleDepthMetric(
                        modulePath = declaration.modulePath,
                        fileCount = declaration.fileCount,
                        declaredSymbolCount = declaration.declaredSymbolCount,
                        internalRefCount = reference.internalRefCount,
                        externalRefCount = reference.externalRefCount,
                        cohesionRatio = cohesionRatio,
                        refsPerFile = refsPerFile,
                        diagnosis = moduleDepthDiagnosis(
                            fileCount = declaration.fileCount,
                            internalRefCount = reference.internalRefCount,
                            externalRefCount = reference.externalRefCount,
                            cohesionRatio = cohesionRatio,
                            refsPerFile = refsPerFile,
                        ),
                        confidence = confidence,
                    )
                }
        }

    fun deadCodeCandidates(filter: FileFilterSpec = FileFilterSpec()): List<DeadCodeCandidate> =
        readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            conn.prepareStatement(
                """
                SELECT names.fq_name,
                       declarations.kind,
                       declarations.visibility,
                       prefixes.dir_path,
                       declarations.filename,
                       declarations.module_path,
                       declarations.source_set
                FROM declarations
                JOIN fq_names names ON names.fq_id = declarations.fq_id
                JOIN path_prefixes prefixes ON prefixes.prefix_id = declarations.prefix_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM symbol_references refs
                    WHERE refs.target_fq_id = declarations.fq_id
                )
                ORDER BY COALESCE(declarations.module_path, '') ASC,
                         prefixes.dir_path ASC,
                         declarations.filename ASC,
                         names.fq_name ASC
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val visibility = rs.getString(3)
                            add(
                                DeadCodeCandidate(
                                    fqName = rs.getString(1),
                                    kind = rs.getString(2),
                                    visibility = visibility,
                                    path = nullablePath(rs, 4, 5),
                                    modulePath = rs.getString(6),
                                    sourceSet = rs.getString(7),
                                    confidence = confidence.forDeadCodeVisibility(visibility),
                                    reason = deadCodeReason(visibility),
                                ),
                            )
                        }
                    }
                }
            }.filterByPath(filter) { it.path }
        }

    fun changeImpactRadius(fqName: String, depth: Int, filter: FileFilterSpec = FileFilterSpec()): List<ChangeImpactNode> {
        require(depth >= 0) { "depth must be non-negative" }
        if (depth == 0) return emptyList()
        return readMetric(emptyList()) { conn ->
            val confidence = currentConfidence(conn)
            if (hasSourceSymbolEdges(conn)) {
                symbolLevelImpact(conn, fqName, depth, confidence)
            } else {
                fileLevelImpact(conn, fqName, depth, confidence)
            }
        }.filterByPath(filter) { it.sourcePath }
    }

    fun searchSymbols(query: String, limit: Int = 25): List<String> {
        require(limit >= 0) { "limit must be non-negative" }
        if (limit == 0) return emptyList()
        val trimmed = query.trim()
        return readMetric(emptyList()) { conn ->
            if (trimmed.isEmpty()) {
                popularSymbols(conn, limit)
            } else {
                val exactAndSubstringMatches = directSymbolMatches(conn, trimmed, limit)
                if (exactAndSubstringMatches.size == limit) {
                    exactAndSubstringMatches
                } else {
                    (exactAndSubstringMatches + fuzzySymbolMatches(conn, trimmed, exactAndSubstringMatches.toSet()))
                        .distinct()
                        .take(limit)
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
            focal?.targetPath?.let { targetPath -> add(targetFileNode(targetPath, focal, childIdsByParent)) }
            impactBySourcePath.forEach { (_, nodesForPath) ->
                val representative = nodesForPath.minBy { it.depth }
                add(sourceFileNode(nodesForPath, childIdsByParent, parentIdFor(representative, impact, fqName)))
                nodesForPath.forEach { node -> add(referenceEdgeNode(node)) }
            }
        }
        val edges = buildList {
            focal?.targetPath?.let { targetPath ->
                add(Edge(from = fileNodeId(targetPath), to = symbolNodeId(fqName), edgeType = EdgeType.CONTAINS))
            }
            impactBySourcePath.forEach { (_, nodesForPath) ->
                val representative = nodesForPath.minBy { it.depth }
                add(
                    Edge(
                        from = parentIdFor(representative, impact, fqName),
                        to = sourceFileNodeId(representative.sourcePath),
                        edgeType = EdgeType.REFERENCED_BY,
                        weight = nodesForPath.sumOf(ChangeImpactNode::occurrenceCount),
                    ),
                )
                nodesForPath.forEach { node ->
                    add(
                        Edge(
                            from = sourceFileNodeId(node.sourcePath),
                            to = referenceEdgeNodeId(node),
                            edgeType = EdgeType.REFERENCES,
                            weight = node.occurrenceCount,
                        ),
                    )
                }
            }
        }
        return Graph(
            focalNodeId = symbolNodeId(fqName),
            nodes = nodes,
            edges = edges,
            index = Index(
                symbolCount = 1 + impact.map(ChangeImpactNode::viaTargetFqName).filterNot { it == fqName }.distinct().size,
                fileCount = listOfNotNull(focal?.targetPath).plus(impact.map(ChangeImpactNode::sourcePath)).distinct().size,
                referenceCount = impact.sumOf(ChangeImpactNode::occurrenceCount),
                maxDepth = impact.maxOfOrNull(ChangeImpactNode::depth) ?: 0,
            ),
        ).toApi()
    }

    override fun close() {
        cachedConnection?.let { conn ->
            if (!conn.isClosed) conn.close()
        }
        cachedConnection = null
    }

    private fun fanInMetric(fqName: String): FanInMetric? =
        fanInRanking(Int.MAX_VALUE).firstOrNull { it.targetFqName == fqName }

    private fun currentConfidence(conn: Connection): Confidence {
        val declarationsCount = countRows(conn, "declarations")
        val identifiersCount = countRows(conn, "identifier_paths")
        val manifestCount = countRows(conn, "file_manifest")
        val indexedFileCount = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(DISTINCT src_prefix_id || ':' || src_filename) FROM symbol_references").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        val completeness = if (manifestCount == 0) 0.0 else indexedFileCount.coerceAtMost(manifestCount) / manifestCount.toDouble()
        val basis = when {
            declarationsCount > 0 -> SemanticBasis.K2_RESOLVED
            identifiersCount > 0 -> SemanticBasis.LEXICAL
            else -> SemanticBasis.HEURISTIC
        }
        val level = when {
            basis == SemanticBasis.K2_RESOLVED && completeness > 0.95 -> ConfidenceLevel.HIGH
            basis == SemanticBasis.K2_RESOLVED && completeness > 0.5 -> ConfidenceLevel.MEDIUM
            basis == SemanticBasis.LEXICAL -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.SPECULATIVE
        }
        return Confidence(level = level, indexCompleteness = completeness, semanticBasis = basis)
    }

    private fun countRows(conn: Connection, tableName: String): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun Confidence.forDeadCodeVisibility(visibility: String): Confidence =
        when {
            semanticBasis != SemanticBasis.K2_RESOLVED -> this
            visibility == "PUBLIC" -> copy(level = ConfidenceLevel.MEDIUM)
            visibility == "INTERNAL" || visibility == "PROTECTED" -> copy(level = ConfidenceLevel.MEDIUM)
            else -> copy(level = ConfidenceLevel.HIGH)
        }

    private fun deadCodeReason(visibility: String): String =
        if (visibility == "PUBLIC") {
            "Declaration has no inbound reference rows; public declarations may still be used externally."
        } else {
            "Declaration has no inbound reference rows in the K2 declaration registry."
        }

    private fun moduleDeclarationStats(conn: Connection): Map<String, ModuleDeclarationStats> {
        val declarationRows = countRows(conn, "declarations")
        val declarationSource = if (declarationRows > 0) {
            "COUNT(declarations.fq_id)"
        } else {
            "COUNT(identifiers.identifier)"
        }
        val declarationJoin = if (declarationRows > 0) {
            """
            LEFT JOIN declarations
              ON declarations.prefix_id = metadata.prefix_id
             AND declarations.filename = metadata.filename
            """.trimIndent()
        } else {
            """
            LEFT JOIN identifier_paths identifiers
              ON identifiers.prefix_id = metadata.prefix_id
             AND identifiers.filename = metadata.filename
            """.trimIndent()
        }
        return conn.prepareStatement(
            """
            SELECT metadata.module_path,
                   COUNT(DISTINCT metadata.prefix_id || ':' || metadata.filename) AS file_count,
                   $declarationSource AS declared_symbol_count
            FROM file_metadata metadata
            $declarationJoin
            WHERE metadata.module_path IS NOT NULL
            GROUP BY metadata.module_path
            """.trimIndent(),
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val modulePath = rs.getString(1)
                        put(modulePath, ModuleDeclarationStats(modulePath, rs.getInt(2), rs.getInt(3)))
                    }
                }
            }
        }
    }

    private fun moduleReferenceStats(conn: Connection): Map<String, ModuleReferenceStats> =
        conn.prepareStatement(
            """
            SELECT source_meta.module_path,
                   SUM(
                       CASE
                           WHEN target_meta.module_path = source_meta.module_path
                            AND (refs.src_prefix_id <> refs.tgt_prefix_id OR refs.src_filename <> refs.tgt_filename)
                           THEN 1
                           ELSE 0
                       END
                   ) AS internal_ref_count,
                   SUM(
                       CASE
                           WHEN target_meta.module_path IS NULL OR target_meta.module_path <> source_meta.module_path
                           THEN 1
                           ELSE 0
                       END
                   ) AS external_ref_count
            FROM symbol_references refs
            JOIN file_metadata source_meta
              ON source_meta.prefix_id = refs.src_prefix_id
             AND source_meta.filename = refs.src_filename
            LEFT JOIN file_metadata target_meta
              ON target_meta.prefix_id = refs.tgt_prefix_id
             AND target_meta.filename = refs.tgt_filename
            WHERE source_meta.module_path IS NOT NULL
            GROUP BY source_meta.module_path
            """.trimIndent(),
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val modulePath = rs.getString(1)
                        put(modulePath, ModuleReferenceStats(modulePath, rs.getInt(2), rs.getInt(3)))
                    }
                }
            }
        }

    private fun exportedSymbolsByModule(conn: Connection): Map<String, Int> =
        groupedCount(conn, "SELECT module_path, COUNT(*) FROM declarations WHERE module_path IS NOT NULL AND visibility IN ('PUBLIC', 'INTERNAL') GROUP BY module_path")

    private fun consumedTargetsByModule(conn: Connection): Map<String, Int> =
        groupedCount(
            conn,
            """
            SELECT source_meta.module_path, COUNT(DISTINCT refs.target_fq_id)
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
            GROUP BY source_meta.module_path
            """.trimIndent(),
        )

    private fun crossModuleReferencesByVisibility(conn: Connection, visibility: String): Map<String, Int> =
        conn.prepareStatement(
            """
            SELECT source_meta.module_path, COUNT(*)
            FROM symbol_references refs
            JOIN file_metadata source_meta
              ON source_meta.prefix_id = refs.src_prefix_id
             AND source_meta.filename = refs.src_filename
            JOIN file_metadata target_meta
              ON target_meta.prefix_id = refs.tgt_prefix_id
             AND target_meta.filename = refs.tgt_filename
            JOIN declarations ON declarations.fq_id = refs.target_fq_id
            WHERE source_meta.module_path IS NOT NULL
              AND target_meta.module_path IS NOT NULL
              AND source_meta.module_path <> target_meta.module_path
              AND declarations.visibility = ?
            GROUP BY source_meta.module_path
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, visibility)
            stmt.executeQuery().use { rs -> rs.stringIntMap() }
        }

    private fun groupedCount(conn: Connection, sql: String): Map<String, Int> =
        conn.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs -> rs.stringIntMap() } }

    private fun ResultSet.stringIntMap(): Map<String, Int> =
        buildMap {
            while (next()) put(getString(1), getInt(2))
        }

    private fun edgeBreakdownsByTarget(conn: Connection): Map<String, Map<String, Int>> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT names.fq_name, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN fq_names names ON names.fq_id = refs.target_fq_id
                GROUP BY names.fq_name, refs.edge_kind
                """.trimIndent(),
            ).use { rs -> nestedEdgeMap(rs) }
        }

    private fun edgeBreakdownsBySource(conn: Connection): Map<String, Map<String, Int>> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT prefixes.dir_path, refs.src_filename, refs.edge_kind, COUNT(*)
                FROM symbol_references refs
                JOIN path_prefixes prefixes ON prefixes.prefix_id = refs.src_prefix_id
                GROUP BY refs.src_prefix_id, refs.src_filename, refs.edge_kind
                """.trimIndent(),
            ).use { rs ->
                buildMap<String, MutableMap<String, Int>> {
                    while (rs.next()) {
                        val key = codec.compose(rs.getString(1), rs.getString(2))
                        getOrPut(key) { mutableMapOf() }[rs.getString(3)] = rs.getInt(4)
                    }
                }
            }
        }

    private fun edgeBreakdownsByModulePair(conn: Connection): Map<Pair<String, String>, Map<String, Int>> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT source_meta.module_path, target_meta.module_path, refs.edge_kind, COUNT(*)
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
                GROUP BY source_meta.module_path, target_meta.module_path, refs.edge_kind
                """.trimIndent(),
            ).use { rs ->
                buildMap<Pair<String, String>, MutableMap<String, Int>> {
                    while (rs.next()) {
                        val key = rs.getString(1) to rs.getString(2)
                        getOrPut(key) { mutableMapOf() }[rs.getString(3)] = rs.getInt(4)
                    }
                }
            }
        }

    private fun nestedEdgeMap(rs: ResultSet): Map<String, Map<String, Int>> =
        buildMap<String, MutableMap<String, Int>> {
            while (rs.next()) {
                val key = rs.getString(1)
                getOrPut(key) { mutableMapOf() }[rs.getString(2)] = rs.getInt(3)
            }
        }

    private fun hasSourceSymbolEdges(conn: Connection): Boolean =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1 FROM symbol_references WHERE source_fq_id IS NOT NULL LIMIT 1").use(ResultSet::next)
        }

    private fun symbolLevelImpact(conn: Connection, fqName: String, depth: Int, confidence: Confidence): List<ChangeImpactNode> =
        conn.prepareStatement(
            """
            WITH RECURSIVE impacted(depth, source_fq_id, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                SELECT 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                FROM symbol_references refs
                WHERE refs.target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                  AND refs.source_fq_id IS NOT NULL
                UNION ALL
                SELECT impacted.depth + 1, refs.source_fq_id, refs.src_prefix_id, refs.src_filename, refs.target_fq_id, refs.edge_kind
                FROM impacted
                JOIN symbol_references refs ON refs.target_fq_id = impacted.source_fq_id
                WHERE impacted.depth < ?
                  AND refs.source_fq_id IS NOT NULL
            )
            SELECT source_prefix.dir_path,
                   impacted.src_filename,
                   impacted.depth,
                   via_target_name.fq_name,
                   impacted.edge_kind,
                   COUNT(*) AS reference_count
            FROM impacted
            JOIN path_prefixes source_prefix ON source_prefix.prefix_id = impacted.src_prefix_id
            JOIN fq_names via_target_name ON via_target_name.fq_id = impacted.via_target_fq_id
            GROUP BY impacted.src_prefix_id, impacted.src_filename, impacted.depth, impacted.via_target_fq_id, impacted.edge_kind
            ORDER BY impacted.depth ASC, reference_count DESC, source_prefix.dir_path ASC, impacted.src_filename ASC, via_target_name.fq_name ASC
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, fqName)
            stmt.setInt(2, depth)
            impactRows(stmt.executeQuery(), confidence)
        }

    private fun fileLevelImpact(conn: Connection, fqName: String, depth: Int, confidence: Confidence): List<ChangeImpactNode> =
        conn.prepareStatement(
            """
            WITH RECURSIVE impacted_files(depth, src_prefix_id, src_filename, via_target_fq_id, edge_kind) AS (
                SELECT 1, src_prefix_id, src_filename, target_fq_id, edge_kind
                FROM symbol_references
                WHERE target_fq_id = (SELECT fq_id FROM fq_names WHERE fq_name = ?)
                UNION ALL
                SELECT impacted_files.depth + 1,
                       refs.src_prefix_id,
                       refs.src_filename,
                       refs.target_fq_id,
                       refs.edge_kind
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
                   impacted_files.edge_kind,
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
             AND refs.edge_kind = impacted_files.edge_kind
            JOIN fq_names via_target_name ON via_target_name.fq_id = impacted_files.via_target_fq_id
            JOIN path_prefixes source_prefix ON source_prefix.prefix_id = first_hits.src_prefix_id
            GROUP BY first_hits.src_prefix_id,
                     first_hits.src_filename,
                     first_hits.depth,
                     impacted_files.via_target_fq_id,
                     via_target_name.fq_name,
                     impacted_files.edge_kind
            ORDER BY first_hits.depth ASC,
                     reference_count DESC,
                     source_prefix.dir_path ASC,
                     first_hits.src_filename ASC,
                     via_target_name.fq_name ASC
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, fqName)
            stmt.setInt(2, depth)
            impactRows(stmt.executeQuery(), confidence)
        }

    private fun impactRows(rs: ResultSet, confidence: Confidence): List<ChangeImpactNode> =
        rs.use {
            buildList {
                while (rs.next()) {
                    add(
                        ChangeImpactNode(
                            sourcePath = codec.compose(rs.getString(1), rs.getString(2)),
                            depth = rs.getInt(3),
                            viaTargetFqName = rs.getString(4),
                            edgeKind = rs.getString(5),
                            occurrenceCount = rs.getInt(6),
                            confidence = confidence,
                        ),
                    )
                }
            }
        }

    private fun popularSymbols(conn: Connection, limit: Int): List<String> =
        conn.prepareStatement(
            """
            SELECT names.fq_name
            FROM fq_names names
            JOIN symbol_references refs ON refs.target_fq_id = names.fq_id
            GROUP BY names.fq_id
            ORDER BY COUNT(*) DESC, names.fq_name ASC
            LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.setInt(1, limit)
            stmt.stringColumnResults()
        }

    private fun directSymbolMatches(conn: Connection, query: String, limit: Int): List<String> =
        conn.prepareStatement(
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
            """.trimIndent(),
        ).use { stmt ->
            val needle = escapeLike(query.lowercase())
            stmt.setString(1, "%$needle%")
            stmt.setString(2, query.lowercase())
            stmt.setString(3, "%.$needle")
            stmt.setString(4, "$needle%")
            stmt.setInt(5, limit)
            stmt.stringColumnResults()
        }

    private fun fuzzySymbolMatches(conn: Connection, query: String, excluded: Set<String>, maxDistance: Int = 2): List<String> {
        val normalizedQuery = query.lowercase()
        return conn.prepareStatement(
            """
            SELECT fq_name
            FROM fq_names
            ORDER BY fq_name ASC
            LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.setInt(1, FUZZY_SYMBOL_CANDIDATE_LIMIT)
            stmt.stringColumnResults()
        }
            .asSequence()
            .filterNot(excluded::contains)
            .mapNotNull { fqName ->
                val simpleName = fqName.substringAfterLast('.').lowercase()
                val fqDistance = levenshteinDistanceAtMost(normalizedQuery, fqName.lowercase(), maxDistance)
                val simpleDistance = levenshteinDistanceAtMost(normalizedQuery, simpleName, maxDistance)
                minOfNotNull(fqDistance, simpleDistance)?.let { distance -> FuzzySymbolMatch(fqName, distance, simpleName.length) }
            }
            .sortedWith(compareBy({ it.distance }, { it.simpleNameLength }, { it.fqName }))
            .map(FuzzySymbolMatch::fqName)
            .toList()
    }

    private fun java.sql.PreparedStatement.stringColumnResults(): List<String> =
        executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.getString(1))
            }
        }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun levenshteinDistanceAtMost(left: String, right: String, maxDistance: Int): Int? {
        if (kotlin.math.abs(left.length - right.length) > maxDistance) return null
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        left.forEachIndexed { leftIndex, leftChar ->
            current[0] = leftIndex + 1
            var rowMinimum = current[0]
            right.forEachIndexed { rightIndex, rightChar ->
                val substitutionCost = if (leftChar == rightChar) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost,
                )
                rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
            }
            if (rowMinimum > maxDistance) return null
            val nextPrevious = previous
            previous = current
            current = nextPrevious
        }
        return previous[right.length].takeIf { it <= maxDistance }
    }

    private fun minOfNotNull(first: Int?, second: Int?): Int? = when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

    private fun buildChildIdsByParent(focal: FanInMetric?, impact: List<ChangeImpactNode>): Map<String, List<String>> =
        buildMap {
            focal?.targetPath?.let { targetPath -> put(fileNodeId(targetPath), listOf(symbolNodeId(focal.targetFqName))) }
            impact.groupBy { parentIdFor(it, impact, focal?.targetFqName ?: it.viaTargetFqName) }
                .forEach { (parentId, children) -> put(parentId, children.map { sourceFileNodeId(it.sourcePath) }.distinct()) }
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
    ): Node {
        val attributes = buildList {
            focal?.targetPath?.let { add("path=$it") }
            focal?.targetModulePath?.let { add("module=$it") }
            focal?.targetSourceSet?.let { add("sourceSet=$it") }
            add("incomingReferences=${focal?.occurrenceCount ?: directReferences.sumOf(ChangeImpactNode::occurrenceCount)}")
            add("sourceFiles=${focal?.sourceFileCount ?: directReferences.map(ChangeImpactNode::sourcePath).distinct().size}")
            focal?.sourceModuleCount?.let { add("sourceModules=$it") }
        }
        return Node(
            id = symbolNodeId(fqName),
            name = fqName,
            type = NodeType.SYMBOL,
            parentId = focal?.targetPath?.let(::fileNodeId),
            children = childIdsByParent[symbolNodeId(fqName)].orEmpty(),
            attributes = attributes,
        )
    }

    private fun targetFileNode(targetPath: String, focal: FanInMetric, childIdsByParent: Map<String, List<String>>): Node =
        Node(
            id = fileNodeId(targetPath),
            name = targetPath,
            type = NodeType.FILE,
            children = childIdsByParent[fileNodeId(targetPath)].orEmpty(),
            attributes = listOfNotNull("role=target", focal.targetModulePath?.let { "module=$it" }, focal.targetSourceSet?.let { "sourceSet=$it" }),
        )

    private fun sourceFileNode(nodes: List<ChangeImpactNode>, childIdsByParent: Map<String, List<String>>, parentId: String): Node {
        val representative = nodes.minBy { it.depth }
        return Node(
            id = sourceFileNodeId(representative.sourcePath),
            name = representative.sourcePath,
            type = NodeType.FILE,
            parentId = parentId,
            children = childIdsByParent[sourceFileNodeId(representative.sourcePath)].orEmpty(),
            attributes = listOf(
                "incomingDepth=${representative.depth}",
                "references=${nodes.sumOf(ChangeImpactNode::occurrenceCount)}",
                "via=${representative.viaTargetFqName}",
            ),
        )
    }

    private fun referenceEdgeNode(node: ChangeImpactNode): Node =
        Node(
            id = referenceEdgeNodeId(node),
            name = node.viaTargetFqName,
            type = NodeType.REFERENCE_EDGE,
            parentId = sourceFileNodeId(node.sourcePath),
            attributes = listOf("from=${node.sourcePath}", "to=${node.viaTargetFqName}", "references=${node.occurrenceCount}"),
        )

    private fun parentIdFor(node: ChangeImpactNode, impact: List<ChangeImpactNode>, fqName: String): String =
        impact
            .firstOrNull { candidate ->
                candidate.depth == node.depth - 1 &&
                    node.viaTargetFqName.substringAfterLast('.') == candidate.sourcePath.substringAfterLast('/').removeSuffix(".kt")
            }
            ?.sourcePath
            ?.let(::sourceFileNodeId)
            ?: symbolNodeId(fqName)

    private fun symbolNodeId(fqName: String): String = "symbol:$fqName"

    private fun fileNodeId(path: String): String = "file:$path"

    private fun sourceFileNodeId(path: String): String = "source-file:$path"

    private fun referenceEdgeNodeId(node: ChangeImpactNode): String = "via:${node.viaTargetFqName}:${node.sourcePath}"

    private fun moduleDepthDiagnosis(
        fileCount: Int,
        internalRefCount: Int,
        externalRefCount: Int,
        cohesionRatio: Double,
        refsPerFile: Double,
    ): ModuleDepthDiagnosis =
        when {
            fileCount > 50 && cohesionRatio > 0.8 -> ModuleDepthDiagnosis.MONOLITH
            cohesionRatio > 0.5 && refsPerFile > 3.0 -> ModuleDepthDiagnosis.DEEP
            internalRefCount == 0 && externalRefCount > 0 -> ModuleDepthDiagnosis.PASS_THROUGH
            cohesionRatio < 0.3 || refsPerFile < 1.0 -> ModuleDepthDiagnosis.SHALLOW
            else -> ModuleDepthDiagnosis.DEEP
        }

    private fun stronglyConnectedComponents(adjacency: Map<String, List<String>>): List<Set<String>> {
        var nextIndex = 0
        val indexByNode = mutableMapOf<String, Int>()
        val lowLinkByNode = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val components = mutableListOf<Set<String>>()

        fun connect(node: String) {
            indexByNode[node] = nextIndex
            lowLinkByNode[node] = nextIndex
            nextIndex += 1
            stack.addLast(node)
            onStack += node
            adjacency[node].orEmpty().forEach { target ->
                if (target !in indexByNode) {
                    connect(target)
                    lowLinkByNode[node] = minOf(checkNotNull(lowLinkByNode[node]), checkNotNull(lowLinkByNode[target]))
                } else if (target in onStack) {
                    lowLinkByNode[node] = minOf(checkNotNull(lowLinkByNode[node]), checkNotNull(indexByNode[target]))
                }
            }
            if (lowLinkByNode[node] == indexByNode[node]) {
                val component = buildSet {
                    do {
                        val member = stack.removeLast()
                        onStack -= member
                        add(member)
                    } while (member != node)
                }
                components += component
            }
        }
        adjacency.keys.sorted().forEach { node ->
            if (node !in indexByNode) connect(node)
        }
        return components
    }

    private fun shortestCycle(component: Set<String>, adjacency: Map<String, List<String>>): List<String>? =
        component.sorted().mapNotNull { start ->
            val queue = ArrayDeque<List<String>>()
            queue.add(listOf(start))
            var found: List<String>? = null
            while (queue.isNotEmpty() && found == null) {
                val path = queue.removeFirst()
                adjacency[path.last()].orEmpty()
                    .filter { it in component }
                    .forEach { next ->
                        when {
                            next == start && path.size > 1 -> found = path + start
                            next !in path -> queue.add(path + next)
                        }
                    }
            }
            found
        }.minWithOrNull(compareBy<List<String>>({ it.size }, { it.joinToString(" -> ") }))

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
        val requiredTables = setOf("path_prefixes", "fq_names", "symbol_references", "identifier_paths", "file_metadata", "file_manifest", "declarations")
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
        SqliteJdbcDriverBootstrap.ensureRegistered()
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA busy_timeout=5000")
            stmt.execute("PRAGMA query_only=ON")
        }
        cachedConnection = conn
        return conn
    }

    private fun nullablePath(rs: ResultSet, dirColumn: Int, filenameColumn: Int): String? {
        val filename = rs.getString(filenameColumn) ?: return null
        return codec.compose(rs.getString(dirColumn), filename)
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator / denominator.toDouble()

    private data class ModuleDeclarationStats(
        val modulePath: String,
        val fileCount: Int,
        val declaredSymbolCount: Int,
    )

    private data class ModuleReferenceStats(
        val modulePath: String,
        val internalRefCount: Int,
        val externalRefCount: Int,
    )

    private data class FuzzySymbolMatch(
        val fqName: String,
        val distance: Int,
        val simpleNameLength: Int,
    )

    private companion object {
        const val FUZZY_SYMBOL_CANDIDATE_LIMIT = 1_000

        val SPECULATIVE_CONFIDENCE = Confidence(
            level = ConfidenceLevel.SPECULATIVE,
            indexCompleteness = 0.0,
            semanticBasis = SemanticBasis.HEURISTIC,
        )
    }
}
