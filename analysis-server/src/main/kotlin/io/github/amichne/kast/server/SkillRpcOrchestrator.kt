package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PageableResult
import io.github.amichne.kast.api.contract.OutlineSymbol
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.skill.KastCallersFailureResponse
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastCallersRequest
import io.github.amichne.kast.api.contract.skill.KastCallersResponse
import io.github.amichne.kast.api.contract.skill.KastCallersSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastCandidate
import io.github.amichne.kast.api.contract.skill.KastDiscoverFailureResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoverQuery
import io.github.amichne.kast.api.contract.skill.KastDiscoverRequest
import io.github.amichne.kast.api.contract.skill.KastDiscoverResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoverSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoveryCandidate
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import io.github.amichne.kast.api.contract.skill.KastMetricsQuery
import io.github.amichne.kast.api.contract.skill.KastMetricsRequest
import io.github.amichne.kast.api.contract.skill.KastMetricsResponse
import io.github.amichne.kast.api.contract.skill.KastMetricsSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastNextRequest
import io.github.amichne.kast.api.contract.skill.KastReferencesFailureResponse
import io.github.amichne.kast.api.contract.skill.KastReferencesQuery
import io.github.amichne.kast.api.contract.skill.KastReferencesRequest
import io.github.amichne.kast.api.contract.skill.KastReferencesResponse
import io.github.amichne.kast.api.contract.skill.KastReferencesSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastRenameByOffsetQuery
import io.github.amichne.kast.api.contract.skill.KastRenameByOffsetRequest
import io.github.amichne.kast.api.contract.skill.KastRenameBySymbolQuery
import io.github.amichne.kast.api.contract.skill.KastRenameBySymbolRequest
import io.github.amichne.kast.api.contract.skill.KastRenameFailureQuery
import io.github.amichne.kast.api.contract.skill.KastRenameFailureResponse
import io.github.amichne.kast.api.contract.skill.KastRenameQuery
import io.github.amichne.kast.api.contract.skill.KastRenameRequest
import io.github.amichne.kast.api.contract.skill.KastRenameResponse
import io.github.amichne.kast.api.contract.skill.KastRenameSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastResolveFailureResponse
import io.github.amichne.kast.api.contract.skill.KastResolveContext
import io.github.amichne.kast.api.contract.skill.KastResolveParams
import io.github.amichne.kast.api.contract.skill.KastResolveQuery
import io.github.amichne.kast.api.contract.skill.KastResolveRequest
import io.github.amichne.kast.api.contract.skill.KastResolveResponse
import io.github.amichne.kast.api.contract.skill.KastResolveSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldFailureResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldQuery
import io.github.amichne.kast.api.contract.skill.KastScaffoldReferences
import io.github.amichne.kast.api.contract.skill.KastScaffoldRequest
import io.github.amichne.kast.api.contract.skill.KastScaffoldResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldTypeHierarchy
import io.github.amichne.kast.api.contract.skill.KastSourceTextWindow
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateCreateFileQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateCreateFileRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateFailureQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateInsertAtOffsetQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateInsertAtOffsetRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateReplaceRangeQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateReplaceRangeRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateResponse
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateSuccessResponse
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.api.contract.skill.WrapperMetric
import io.github.amichne.kast.api.contract.skill.WrapperNamedSymbolKind
import io.github.amichne.kast.api.contract.skill.WrapperScaffoldMode
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.indexstore.api.metrics.general.DeclarationInfo
import io.github.amichne.kast.indexstore.api.metrics.general.FileFilterSpec
import io.github.amichne.kast.indexstore.api.metrics.impact.ChangeImpactNode
import io.github.amichne.kast.indexstore.api.metrics.impact.DeadCodeCandidate
import io.github.amichne.kast.indexstore.api.metrics.impact.FanInMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.FanOutMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.LowUsageSymbol
import io.github.amichne.kast.indexstore.api.metrics.module.ApiSurfaceMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleBoundaryMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCouplingMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCycleMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleDepthMetric
import io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryDeclarationMatch as StoreSymbolQueryDeclarationMatch
import io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryFilters as StoreSymbolQueryFilters
import io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryGraphDirection as StoreSymbolQueryGraphDirection
import io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryGraphEdge as StoreSymbolQueryGraphEdge
import io.github.amichne.kast.indexstore.api.reference.EdgeKind
import io.github.amichne.kast.indexstore.metrics.MetricsEngine
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal class SkillRpcOrchestrator(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val json: Json,
) {
    private companion object {
        const val DEFAULT_DISCOVERY_SEARCH_LIMIT = 100
        const val MAX_SURROUNDING_LINES = 50
    }

    suspend fun resolve(request: KastResolveRequest): KastResolveResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastResolveQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            includeDeclarationScope = request.includeDeclarationScope,
            includeDocumentation = request.includeDocumentation,
            surroundingLines = request.surroundingLines,
            includeSurroundingMembers = request.includeSurroundingMembers,
        )
        validateResolveQuery(query)
        val candidates = rankedNamedSymbolCandidates(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            line = null,
            codeSnippet = null,
            includeDeclarationScope = false,
            searchLimit = minOf(config.maxResults, DEFAULT_DISCOVERY_SEARCH_LIMIT),
        )
        val candidate = candidates.firstOrNull() ?: return KastResolveFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = placeholderLogFile(),
        )
        val resolved = resolveNamedSymbol(
            candidate = candidate,
            includeDeclarationScope = request.includeDeclarationScope,
            includeDocumentation = request.includeDocumentation,
        ) ?: return KastResolveFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = placeholderLogFile(),
        )
        val context = resolveContext(resolved.symbol, request)
        return KastResolveSuccessResponse(
            query = query,
            symbol = resolved.symbol,
            filePath = resolved.filePath,
            offset = resolved.offset,
            candidate = KastCandidate(
                line = resolved.symbol.location.startLine,
                column = resolved.symbol.location.startColumn,
                context = resolved.symbol.location.preview,
            ),
            candidateCount = candidates.size.takeIf { it > 1 },
            alternatives = candidates
                .asSequence()
                .map { it.symbol.fqName }
                .filter { it != candidate.symbol.fqName }
                .distinct()
                .take(3)
                .toList()
                .takeIf { it.isNotEmpty() },
            context = context,
            logFile = placeholderLogFile(),
        )
    }

    suspend fun discover(request: KastDiscoverRequest): KastDiscoverResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastDiscoverQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            line = request.line,
            codeSnippet = request.codeSnippet,
            kind = request.kind,
            containingType = request.containingType,
            maxResults = request.maxResults,
            includeDeclarationScope = request.includeDeclarationScope,
        )
        validateDiscoverQuery(query)
        val searchLimit = minOf(
            config.maxResults,
            maxOf(request.maxResults + 1, DEFAULT_DISCOVERY_SEARCH_LIMIT),
        )
        val candidates = rankedNamedSymbolCandidates(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            line = request.line,
            codeSnippet = request.codeSnippet,
            includeDeclarationScope = request.includeDeclarationScope,
            searchLimit = searchLimit,
        )
        val visibleCandidates = candidates.take(request.maxResults)
        return KastDiscoverSuccessResponse(
            query = query,
            candidates = visibleCandidates.mapIndexed { index, candidate ->
                candidate.toDiscoveryCandidate(
                    rank = index + 1,
                    workspaceRoot = workspaceRoot,
                    requestedSymbol = request.symbol,
                )
            },
            page = if (candidates.size > visibleCandidates.size) {
                PageInfo(
                    truncated = true,
                    nextPageToken = visibleCandidates.size.toString(),
                )
            } else {
                null
            },
            logFile = placeholderLogFile(),
        )
    }

    suspend fun symbolQuery(request: KastSymbolQueryRequest): KastSymbolQueryResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val queryText = request.query.trim()
        val failure = validateSymbolQueryRequest(request, queryText)
        if (failure != null) return failure
        val workspacePath = Path.of(workspaceRoot)
        if (!Files.isRegularFile(sourceIndexDatabasePath(workspacePath))) {
            return symbolQueryFailure(
                reason = SymbolQueryFailureReason.INDEX_UNAVAILABLE,
                stage = "index",
                message = "No source index database is available for workspace '$workspaceRoot'",
                query = queryText,
            )
        }

        val modes = request.modes.toSet()
        val limit = minOf(request.limit, config.maxResults)
        val filters = request.filters.toStoreFilters()
        val graphDepth = request.graph.depth.coerceAtMost(2)
        val graphEdgeKinds = request.graph.edgeKinds.map { EdgeKind.valueOf(it.name) }.toSet()
        val graphDirection = request.graph.direction.toStoreGraphDirection()

        val queryResults: SymbolQueryEngineResults = MetricsEngine(workspacePath).use { engine ->
            val anchor = resolveSymbolQueryAnchor(engine, request.anchor, filters)
            if (anchor is SymbolQueryAnchorResolution.Failure) {
                return symbolQueryFailure(
                    reason = anchor.reason,
                    stage = "anchor",
                    message = anchor.message,
                    query = queryText,
                )
            }

            val resolvedAnchor = (anchor as? SymbolQueryAnchorResolution.Success)?.declaration
            if (resolvedAnchor != null && SymbolQueryMode.GRAPH in modes) {
                val graphEdges = engine.symbolQueryGraphEdges(
                    startFqIds = setOf(resolvedAnchor.fqId),
                    direction = graphDirection,
                    edgeKinds = graphEdgeKinds,
                    depth = graphDepth,
                    maxEdgesPerResult = request.graph.maxEdgesPerResult,
                )
                val graphDeclarationIds = graphEdges.map(StoreSymbolQueryGraphEdge::resultFqId).toSet()
                val graphDeclarations = engine.symbolQueryDeclarationsByFqIds(
                    fqIds = graphDeclarationIds,
                    filters = filters,
                    limit = limit,
                )
                val queryDeclarations = if (queryText.isNotEmpty() && modes.any { it == SymbolQueryMode.EXACT || it == SymbolQueryMode.LEXICAL || it == SymbolQueryMode.STRUCTURAL }) {
                    engine.symbolQueryDeclarations(query = queryText, filters = filters, limit = limit)
                } else {
                    emptyList()
                }
                SymbolQueryEngineResults(
                    declarations = (graphDeclarations + queryDeclarations).distinctBy(StoreSymbolQueryDeclarationMatch::fqId),
                    graphEdgesByDeclarationId = graphEdges.groupBy(StoreSymbolQueryGraphEdge::resultFqId),
                )
            } else {
                val declarations = if (queryText.isEmpty()) {
                    engine.symbolQueryByFilters(filters = filters, limit = limit)
                } else {
                    engine.symbolQueryDeclarations(query = queryText, filters = filters, limit = limit)
                }
                val graphEdges = if (SymbolQueryMode.GRAPH in modes && declarations.isNotEmpty()) {
                    engine.symbolQueryGraphEdges(
                        startFqIds = declarations.map(StoreSymbolQueryDeclarationMatch::fqId).toSet(),
                        direction = graphDirection,
                        edgeKinds = graphEdgeKinds,
                        depth = graphDepth,
                        maxEdgesPerResult = request.graph.maxEdgesPerResult,
                    )
                } else {
                    emptyList()
                }
                SymbolQueryEngineResults(
                    declarations = declarations,
                    graphEdgesByDeclarationId = graphEdges.groupBy(StoreSymbolQueryGraphEdge::originFqId),
                )
            }
        }

        val rankedResults = queryResults.declarations
            .map { declaration ->
                declaration.toSymbolQueryResult(
                    workspaceRoot = workspaceRoot,
                    modes = modes,
                    graphEdges = queryResults.graphEdgesByDeclarationId[declaration.fqId].orEmpty(),
                    includeEvidence = request.includeEvidence,
                    includeNextRequests = request.includeNextRequests,
                    maxGraphEdges = request.graph.maxEdgesPerResult,
                )
            }
            .sortedWith(
                compareByDescending<SymbolQueryResult> { it.rank.sortScore }
                    .thenBy { it.declaration.fqName }
                    .thenBy { it.declaration.file.path },
            )
            .take(limit)
            .mapIndexed { index, result ->
                result.copy(rank = result.rank.copy(position = index + 1))
            }

        return KastSymbolQuerySuccessResponse(
            query = queryText,
            availableSignals = AvailableSignals(
                exact = SymbolQueryMode.EXACT in modes,
                lexical = SymbolQueryMode.LEXICAL in modes,
                structural = SymbolQueryMode.STRUCTURAL in modes,
                graph = SymbolQueryMode.GRAPH in modes,
                semantic = false,
            ),
            hardFilters = request.filters.toHardFilters(),
            results = rankedResults,
            logFile = placeholderLogFile(),
        )
    }

    suspend fun references(request: KastReferencesRequest): KastReferencesResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastReferencesQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            includeDeclaration = request.includeDeclaration,
        )
        val resolved = resolveNamedSymbol(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        ) ?: return KastReferencesFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = placeholderLogFile(),
        )
        requireReadCapability(ReadCapability.FIND_REFERENCES)
        val result = backend.findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                includeDeclaration = request.includeDeclaration,
            ).parsed(),
        ).withLimit(config.maxResults, ::referencePageToken)
        return KastReferencesSuccessResponse(
            query = query,
            symbol = resolved.symbol,
            filePath = resolved.filePath,
            offset = resolved.offset,
            references = result.references,
            searchScope = result.searchScope,
            declaration = result.declaration,
            candidateCount = resolved.candidateCount.takeIf { it > 1 },
            alternatives = resolved.alternativeFqNames.takeIf { it.isNotEmpty() },
            logFile = placeholderLogFile(),
        )
    }

    suspend fun callers(request: KastCallersRequest): KastCallersResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastCallersQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            direction = request.direction,
            depth = request.depth,
            maxTotalCalls = request.maxTotalCalls,
            maxChildrenPerNode = request.maxChildrenPerNode,
            timeoutMillis = request.timeoutMillis,
        )
        val resolved = resolveNamedSymbol(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        ) ?: return KastCallersFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = placeholderLogFile(),
        )
        requireReadCapability(ReadCapability.CALL_HIERARCHY)
        val result = backend.callHierarchy(
            CallHierarchyQuery(
                position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                direction = request.direction.toCallDirection(),
                depth = request.depth,
                maxTotalCalls = request.maxTotalCalls ?: 256,
                maxChildrenPerNode = request.maxChildrenPerNode ?: 64,
                timeoutMillis = request.timeoutMillis?.toLong(),
            ).parsed(),
        )
        return KastCallersSuccessResponse(
            query = query,
            symbol = resolved.symbol,
            filePath = resolved.filePath,
            offset = resolved.offset,
            root = result.root,
            stats = result.stats,
            candidateCount = resolved.candidateCount.takeIf { it > 1 },
            alternatives = resolved.alternativeFqNames.takeIf { it.isNotEmpty() },
            logFile = placeholderLogFile(),
        )
    }

    suspend fun scaffold(request: KastScaffoldRequest): KastScaffoldResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val targetFile = request.targetFile.normalizedAbsolutePath()
        val query = KastScaffoldQuery(
            workspaceRoot = workspaceRoot,
            targetFile = request.targetFile,
            targetSymbol = request.targetSymbol,
            mode = request.mode,
            kind = request.kind,
        )
        requireReadCapability(ReadCapability.FILE_OUTLINE)
        val outline = backend.fileOutline(FileOutlineQuery(filePath = targetFile).parsed()).symbols
        val resolvedSymbol = request.targetSymbol?.let { symbolName ->
            resolveNamedSymbol(
                symbolName = symbolName,
                fileHint = request.targetFile,
                kind = request.kind,
                containingType = null,
            )
        }
        val references = resolvedSymbol?.let { resolved ->
            requireReadCapability(ReadCapability.FIND_REFERENCES)
            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                    includeDeclaration = true,
                ).parsed(),
            ).withLimit(config.maxResults, ::referencePageToken)
            KastScaffoldReferences(
                locations = result.references,
                count = result.references.size,
                searchScope = result.searchScope,
                declaration = result.declaration,
            )
        }
        val typeHierarchy = resolvedSymbol?.takeIf { it.symbol.kind in setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT) }?.let { resolved ->
            requireReadCapability(ReadCapability.TYPE_HIERARCHY)
            val result = backend.typeHierarchy(
                TypeHierarchyQuery(
                    position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                ).parsed(),
            )
            KastScaffoldTypeHierarchy(root = result.root, stats = result.stats)
        }
        val insertionPoint = resolvedSymbol?.let { resolved ->
            requireReadCapability(ReadCapability.SEMANTIC_INSERTION_POINT)
            backend.semanticInsertionPoint(
                io.github.amichne.kast.api.contract.SemanticInsertionQuery(
                    position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                    target = request.mode.toInsertionTarget(),
                ).parsed(),
            )
        }
        val fileContent = targetFile.readTextIfPresent()
        return KastScaffoldSuccessResponse(
            query = query,
            outline = outline,
            fileContent = fileContent,
            symbol = resolvedSymbol?.symbol,
            references = references,
            typeHierarchy = typeHierarchy,
            insertionPoint = insertionPoint,
            logFile = placeholderLogFile(),
        )
    }

    suspend fun rename(request: KastRenameRequest): KastRenameResponse = when (request) {
        is KastRenameBySymbolRequest -> renameBySymbol(request)
        is KastRenameByOffsetRequest -> renameByOffset(request)
    }

    suspend fun writeAndValidate(request: KastWriteAndValidateRequest): KastWriteAndValidateResponse = when (request) {
        is KastWriteAndValidateCreateFileRequest -> writeAndValidateCreate(request)
        is KastWriteAndValidateInsertAtOffsetRequest -> writeAndValidateInsert(request)
        is KastWriteAndValidateReplaceRangeRequest -> writeAndValidateReplace(request)
    }

    suspend fun metrics(request: KastMetricsRequest): KastMetricsResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastMetricsQuery(
            workspaceRoot = workspaceRoot,
            metric = request.metric,
            limit = request.limit,
            symbol = request.symbol,
            depth = request.depth,
            fileGlob = request.fileGlob,
            folderFilter = request.folderFilter,
        )
        val filter = FileFilterSpec(
            fileGlob = request.fileGlob,
            folderPrefix = request.folderFilter,
        )
        val results = MetricsEngine(Path.of(workspaceRoot)).use { engine ->
            when (request.metric) {
                WrapperMetric.API_SURFACE -> json.encodeList(ApiSurfaceMetric.serializer(), engine.apiSurface(modulePath = request.symbol))
                WrapperMetric.MODULE_BOUNDARY -> json.encodeList(ModuleBoundaryMetric.serializer(), engine.moduleBoundary(modulePath = request.symbol))
                WrapperMetric.DECLARATIONS -> json.encodeList(DeclarationInfo.serializer(), engine.declarations(filter))
                WrapperMetric.FAN_IN -> json.encodeList(FanInMetric.serializer(), engine.fanInRanking(request.limit, filter))
                WrapperMetric.FAN_OUT -> json.encodeList(FanOutMetric.serializer(), engine.fanOutRanking(request.limit, filter))
                WrapperMetric.COUPLING -> json.encodeList(ModuleCouplingMetric.serializer(), engine.moduleCouplingMatrix())
                WrapperMetric.LOW_USAGE -> json.encodeList(LowUsageSymbol.serializer(), engine.lowUsageSymbols(limit = request.limit, filter = filter))
                WrapperMetric.CYCLES -> json.encodeList(ModuleCycleMetric.serializer(), engine.moduleCycles())
                WrapperMetric.MODULE_DEPTH -> json.encodeList(ModuleDepthMetric.serializer(), engine.moduleDepthMetrics())
                WrapperMetric.DEAD_CODE -> json.encodeList(DeadCodeCandidate.serializer(), engine.deadCodeCandidates(filter))
                WrapperMetric.IMPACT -> json.encodeList(
                    ChangeImpactNode.serializer(),
                    engine.changeImpactRadius(
                        fqName = request.symbol ?: throw ValidationException("'symbol' is required for impact metric"),
                        depth = request.depth,
                        filter = filter,
                    ),
                )
            }
        }
        return KastMetricsSuccessResponse(
            query = query,
            results = results,
            logFile = placeholderLogFile(),
        )
    }

    private fun validateSymbolQueryRequest(
        request: KastSymbolQueryRequest,
        queryText: String,
    ): KastSymbolQueryFailureResponse? {
        if (request.modes.isEmpty()) {
            return symbolQueryFailure(SymbolQueryFailureReason.INVALID_FILTER, "validate", "modes must not be empty", queryText)
        }
        if (queryText.isEmpty() && !request.anchor.hasAnchorValue()) {
            return symbolQueryFailure(
                SymbolQueryFailureReason.QUERY_TOO_BROAD,
                "validate",
                "query may be empty only when anchor is provided",
                queryText,
            )
        }
        if (request.limit <= 0) {
            return symbolQueryFailure(SymbolQueryFailureReason.INVALID_FILTER, "validate", "limit must be greater than 0", queryText)
        }
        if (request.graph.depth < 0) {
            return symbolQueryFailure(SymbolQueryFailureReason.INVALID_FILTER, "validate", "graph.depth must be non-negative", queryText)
        }
        if (request.graph.maxEdgesPerResult <= 0) {
            return symbolQueryFailure(SymbolQueryFailureReason.INVALID_FILTER, "validate", "graph.maxEdgesPerResult must be greater than 0", queryText)
        }
        if (request.semantic.maxCandidates < 0) {
            return symbolQueryFailure(SymbolQueryFailureReason.INVALID_FILTER, "validate", "semantic.maxCandidates must be non-negative", queryText)
        }
        return null
    }

    private fun resolveSymbolQueryAnchor(
        engine: MetricsEngine,
        anchor: KastSymbolQueryAnchor?,
        filters: StoreSymbolQueryFilters,
    ): SymbolQueryAnchorResolution? {
        if (anchor == null || !anchor.hasAnchorValue()) return null
        anchor.fqName?.takeIf(String::isNotBlank)?.let { fqName ->
            val matches = engine.symbolQueryDeclarations(query = fqName, limit = 3)
                .filter { it.fqName == fqName }
            return singleAnchorMatch(matches, "fqName '$fqName'")
        }
        anchor.symbol?.takeIf(String::isNotBlank)?.let { symbol ->
            val matches = engine.symbolQueryDeclarations(query = symbol, filters = filters, limit = 3)
                .filter { it.simpleName == symbol || it.fqName == symbol }
            return singleAnchorMatch(matches, "symbol '$symbol'")
        }
        val filePath = anchor.filePath?.takeIf(String::isNotBlank)?.normalizedAbsolutePath()
        val offset = anchor.offset
        if (filePath != null) {
            val matches = engine.symbolQueryByFilters(
                filters = filters.copy(fileGlob = filePath),
                limit = 20,
            ).filter { declaration -> offset == null || declaration.declarationOffset == offset }
            return singleAnchorMatch(matches, "filePath '$filePath'${offset?.let { " offset $it" }.orEmpty()}")
        }
        return SymbolQueryAnchorResolution.Failure(
            reason = SymbolQueryFailureReason.ANCHOR_NOT_FOUND,
            message = "Anchor did not include fqName, symbol, or filePath",
        )
    }

    private fun singleAnchorMatch(
        matches: List<StoreSymbolQueryDeclarationMatch>,
        label: String,
    ): SymbolQueryAnchorResolution = when (matches.size) {
        0 -> SymbolQueryAnchorResolution.Failure(
            reason = SymbolQueryFailureReason.ANCHOR_NOT_FOUND,
            message = "No indexed declaration matched anchor $label",
        )

        1 -> SymbolQueryAnchorResolution.Success(matches.single())
        else -> SymbolQueryAnchorResolution.Failure(
            reason = SymbolQueryFailureReason.AMBIGUOUS_ANCHOR,
            message = "Anchor $label matched ${matches.size} declarations",
        )
    }

    private fun StoreSymbolQueryDeclarationMatch.toSymbolQueryResult(
        workspaceRoot: String,
        modes: Set<SymbolQueryMode>,
        graphEdges: List<StoreSymbolQueryGraphEdge>,
        includeEvidence: Boolean,
        includeNextRequests: Boolean,
        maxGraphEdges: Int,
    ): SymbolQueryResult {
        val exactMatches = if (SymbolQueryMode.EXACT in modes) exactMatches.map { it.toSignalMatch() } else emptyList()
        val lexicalMatches = if (SymbolQueryMode.LEXICAL in modes) lexicalMatches.map { it.toSignalMatch() } else emptyList()
        val structuralConstraints = structuralConstraints.map { it.toStructuralConstraint() }
        val graphPaths = if (SymbolQueryMode.GRAPH in modes) {
            graphEdges.take(maxGraphEdges).map { it.toGraphPath() }
        } else {
            emptyList()
        }
        val components = SymbolQueryRankComponents(
            exact = exactMatches.exactComponentScore(),
            lexical = lexicalMatches.lexicalComponentScore(),
            structural = if (structuralConstraints.isEmpty()) 0.0 else 1.0,
            graph = if (graphPaths.isEmpty()) 0.0 else (graphPaths.size.toDouble() / maxGraphEdges).coerceAtMost(1.0),
            semantic = null,
        )
        val declaration = toWireDeclaration()
        return SymbolQueryResult(
            declaration = declaration,
            rank = SymbolQueryRank(
                position = 0,
                sortScore = components.sortScore(),
                components = components,
            ),
            signals = SymbolQuerySignals(
                exact = SymbolQueryExactSignal(
                    matched = exactMatches.isNotEmpty(),
                    matches = exactMatches.takeIf { includeEvidence }.orEmpty(),
                ),
                lexical = SymbolQueryLexicalSignal(
                    matched = lexicalMatches.isNotEmpty(),
                    matches = lexicalMatches.takeIf { includeEvidence }.orEmpty(),
                ),
                structural = SymbolQueryStructuralSignal(
                    matched = structuralConstraints.isNotEmpty(),
                    constraints = structuralConstraints.takeIf { includeEvidence }.orEmpty(),
                ),
                graph = SymbolQueryGraphSignal(
                    matched = graphPaths.isNotEmpty(),
                    paths = graphPaths.takeIf { includeEvidence }.orEmpty(),
                ),
                semantic = SymbolQuerySemanticSignal(
                    available = false,
                    matched = false,
                    discoveryOnly = true,
                    reason = "No semantic projection index configured",
                ),
            ),
            nextRequests = declaration.nextRequests(workspaceRoot).takeIf { includeNextRequests },
        )
    }

    private fun StoreSymbolQueryDeclarationMatch.toWireDeclaration(): SymbolQueryDeclaration =
        SymbolQueryDeclaration(
            fqId = fqId,
            fqName = fqName,
            simpleName = simpleName,
            kind = kind,
            visibility = visibility,
            modulePath = modulePath,
            sourceSet = sourceSet,
            file = SymbolQueryDeclarationFile(
                prefixId = prefixId,
                dirPath = dirPath,
                filename = filename,
                path = path,
            ),
            declarationOffset = declarationOffset,
        )

    private fun io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryFieldMatch.toSignalMatch(): SymbolQuerySignalMatch =
        SymbolQuerySignalMatch(
            field = field,
            term = term,
            matchType = matchType,
            evidence = evidence,
        )

    private fun io.github.amichne.kast.indexstore.api.metrics.symbolquery.SymbolQueryConstraint.toStructuralConstraint(): SymbolQueryStructuralConstraint =
        SymbolQueryStructuralConstraint(
            field = field,
            operator = operator,
            value = if (value.size == 1) JsonPrimitive(value.single()) else JsonArray(value.map(::JsonPrimitive)),
            source = source,
        )

    private fun StoreSymbolQueryGraphEdge.toGraphPath(): SymbolQueryGraphPath =
        SymbolQueryGraphPath(
            fromFqName = fromFqName,
            edgeKind = edgeKind,
            toFqName = toFqName,
            sourceFile = sourceFile,
            sourceOffset = sourceOffset,
            depth = depth,
        )

    private fun List<SymbolQuerySignalMatch>.exactComponentScore(): Double =
        maxOfOrNull { match ->
            when (match.matchType) {
                "EQUALS" -> 1.0
                "SIMPLE_NAME_EQUALS" -> 0.9
                "PREFIX_EQUALS" -> 0.75
                else -> 0.0
            }
        } ?: 0.0

    private fun List<SymbolQuerySignalMatch>.lexicalComponentScore(): Double =
        if (isEmpty()) 0.0 else (size / 6.0).coerceAtMost(1.0)

    private fun SymbolQueryRankComponents.sortScore(): Double =
        (exact * 0.45) + (lexical * 0.25) + (structural * 0.15) + (graph * 0.15) + ((semantic ?: 0.0) * 0.0)

    private fun KastSymbolQueryFilters.toStoreFilters(): StoreSymbolQueryFilters =
        StoreSymbolQueryFilters(
            kinds = kinds.map(SymbolQueryDeclarationKind::name).toSet(),
            visibility = visibility.map(SymbolQueryVisibility::name).toSet(),
            modulePath = modulePath,
            sourceSet = sourceSet,
            fileGlob = fileGlob,
            packagePrefix = packagePrefix,
            fqNamePrefix = fqNamePrefix,
        )

    private fun SymbolQueryGraphDirection.toStoreGraphDirection(): StoreSymbolQueryGraphDirection =
        when (this) {
            SymbolQueryGraphDirection.INCOMING -> StoreSymbolQueryGraphDirection.INCOMING
            SymbolQueryGraphDirection.OUTGOING -> StoreSymbolQueryGraphDirection.OUTGOING
            SymbolQueryGraphDirection.BOTH -> StoreSymbolQueryGraphDirection.BOTH
        }

    private fun KastSymbolQueryFilters.toHardFilters(): List<HardFilter> = buildList {
        kinds.forEach { add(HardFilter("kinds", it.name, "declarations.kind", satisfiedSymbolically = true)) }
        visibility.forEach { add(HardFilter("visibility", it.name, "declarations.visibility", satisfiedSymbolically = true)) }
        modulePath?.let { add(HardFilter("modulePath", it, "declarations.module_path", satisfiedSymbolically = true)) }
        sourceSet?.let { add(HardFilter("sourceSet", it, "declarations.source_set", satisfiedSymbolically = true)) }
        fileGlob?.let { add(HardFilter("fileGlob", it, "path_prefixes.dir_path || '/' || declarations.filename", satisfiedSymbolically = true)) }
        packagePrefix?.let { add(HardFilter("packagePrefix", it, "file_metadata.package_fq_id", satisfiedSymbolically = true)) }
        fqNamePrefix?.let { add(HardFilter("fqNamePrefix", it, "fq_names.fq_name", satisfiedSymbolically = true)) }
    }

    private fun SymbolQueryDeclaration.nextRequests(workspaceRoot: String): SymbolQueryNextRequests {
        val kind = kind.toNamedSymbolKindRequestValue()
        return SymbolQueryNextRequests(
            symbolResolve = SymbolQueryNextRequest(
                method = "symbol/resolve",
                request = jsonObjectOf(
                    "workspaceRoot" to JsonPrimitive(workspaceRoot),
                    "symbol" to JsonPrimitive(simpleName),
                    "fileHint" to JsonPrimitive(file.path),
                    "kind" to kind?.let(::JsonPrimitive),
                    "includeDeclarationScope" to JsonPrimitive(true),
                ),
            ),
            symbolReferences = SymbolQueryNextRequest(
                method = "symbol/references",
                request = jsonObjectOf(
                    "workspaceRoot" to JsonPrimitive(workspaceRoot),
                    "symbol" to JsonPrimitive(simpleName),
                    "fileHint" to JsonPrimitive(file.path),
                    "kind" to kind?.let(::JsonPrimitive),
                    "includeDeclaration" to JsonPrimitive(true),
                ),
            ),
            symbolCallers = SymbolQueryNextRequest(
                method = "symbol/callers",
                request = jsonObjectOf(
                    "workspaceRoot" to JsonPrimitive(workspaceRoot),
                    "symbol" to JsonPrimitive(simpleName),
                    "fileHint" to JsonPrimitive(file.path),
                    "kind" to kind?.let(::JsonPrimitive),
                    "direction" to JsonPrimitive("incoming"),
                    "depth" to JsonPrimitive(1),
                ),
            ),
            rawResolve = declarationOffset?.let { offset ->
                SymbolQueryNextRequest(
                    method = "raw/resolve",
                    request = jsonObjectOf(
                        "position" to jsonObjectOf(
                            "filePath" to JsonPrimitive(file.path),
                            "offset" to JsonPrimitive(offset),
                        ),
                    ),
                )
            },
        )
    }

    private fun String.toNamedSymbolKindRequestValue(): String? = when (this) {
        "CLASS" -> "class"
        "INTERFACE" -> "interface"
        "OBJECT" -> "object"
        "FUNCTION", "CONSTRUCTOR" -> "function"
        "PROPERTY" -> "property"
        else -> null
    }

    private fun symbolQueryFailure(
        reason: SymbolQueryFailureReason,
        stage: String,
        message: String,
        query: String,
    ): KastSymbolQueryFailureResponse =
        KastSymbolQueryFailureResponse(
            reason = reason,
            stage = stage,
            message = message,
            query = query,
            logFile = placeholderLogFile(),
        )

    private suspend fun renameBySymbol(request: KastRenameBySymbolRequest): KastRenameResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val resolved = resolveNamedSymbol(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        ) ?: return KastRenameFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = KastRenameFailureQuery(
                workspaceRoot = workspaceRoot,
                symbol = request.symbol,
                fileHint = request.fileHint,
                kind = request.kind,
                containingType = request.containingType,
                newName = request.newName,
            ),
            logFile = placeholderLogFile(),
        )
        return performRename(
            filePath = resolved.filePath,
            offset = resolved.offset,
            newName = request.newName,
            queryBuilder = {
                KastRenameBySymbolQuery(
                    workspaceRoot = workspaceRoot,
                    symbol = request.symbol,
                    newName = request.newName,
                    fileHint = request.fileHint,
                    kind = request.kind,
                    containingType = request.containingType,
                    filePath = resolved.filePath,
                    offset = resolved.offset,
                )
            },
            failureQueryBuilder = {
                KastRenameFailureQuery(
                    workspaceRoot = workspaceRoot,
                    symbol = request.symbol,
                    fileHint = request.fileHint,
                    kind = request.kind,
                    containingType = request.containingType,
                    newName = request.newName,
                )
            },
        )
    }

    private suspend fun renameByOffset(request: KastRenameByOffsetRequest): KastRenameResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val filePath = request.filePath.normalizedAbsolutePath()
        return performRename(
            filePath = filePath,
            offset = request.offset,
            newName = request.newName,
            queryBuilder = {
                KastRenameByOffsetQuery(
                    workspaceRoot = workspaceRoot,
                    filePath = filePath,
                    offset = request.offset,
                    newName = request.newName,
                )
            },
            failureQueryBuilder = {
                KastRenameFailureQuery(
                    workspaceRoot = workspaceRoot,
                    filePath = filePath,
                    offset = request.offset,
                    newName = request.newName,
                )
            },
        )
    }

    private suspend fun performRename(
        filePath: String,
        offset: Int,
        newName: String,
        queryBuilder: () -> KastRenameQuery,
        failureQueryBuilder: () -> KastRenameFailureQuery,
    ): KastRenameResponse {
        requireMutationCapability(MutationCapability.RENAME)
        val renameResult = backend.rename(
            RenameQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                newName = newName,
                dryRun = true,
            ).parsed(),
        )
        requireMutationCapability(MutationCapability.APPLY_EDITS)
        val applyResult = backend.applyEdits(
            ApplyEditsQuery(
                edits = renameResult.edits,
                fileHashes = renameResult.fileHashes,
            ).parsed(),
        )
        val diagnosticsSummary = if (renameResult.affectedFiles.isEmpty()) {
            KastDiagnosticsSummary(clean = true, errorCount = 0, warningCount = 0)
        } else {
            requireReadCapability(ReadCapability.DIAGNOSTICS)
            diagnosticsSummary(backend.diagnostics(DiagnosticsQuery(filePaths = renameResult.affectedFiles).parsed()).withLimit(config.maxResults, ::diagnosticPageToken))
        }
        return KastRenameSuccessResponse(
            ok = diagnosticsSummary.clean,
            query = queryBuilder(),
            editCount = renameResult.edits.size,
            affectedFiles = renameResult.affectedFiles,
            applyResult = applyResult,
            diagnostics = diagnosticsSummary,
            logFile = placeholderLogFile(),
        )
    }

    private suspend fun writeAndValidateCreate(request: KastWriteAndValidateCreateFileRequest): KastWriteAndValidateResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val filePath = request.filePath.normalizedAbsolutePath()
        val content = resolveContent(request.content, request.contentFile)
        requireMutationCapability(MutationCapability.APPLY_EDITS)
        requireMutationCapability(MutationCapability.FILE_OPERATIONS)
        val applyResult = backend.applyEdits(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(FileOperation.CreateFile(filePath = filePath, content = content)),
            ).parsed(),
        )
        val optimized = optimizeImports(filePath)
        val diagnostics = validateFiles(listOf(filePath))
        return KastWriteAndValidateSuccessResponse(
            ok = diagnostics.clean,
            query = KastWriteAndValidateCreateFileQuery(
                workspaceRoot = workspaceRoot,
                filePath = request.filePath,
            ),
            appliedEdits = applyResult.applied.size + applyResult.createdFiles.size,
            importChanges = optimized.edits.size,
            diagnostics = diagnostics,
            logFile = placeholderLogFile(),
        )
    }

    private suspend fun writeAndValidateInsert(request: KastWriteAndValidateInsertAtOffsetRequest): KastWriteAndValidateResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val filePath = request.filePath.normalizedAbsolutePath()
        val content = resolveContent(request.content, request.contentFile)
        val edit = TextEdit(
            filePath = filePath,
            startOffset = request.offset,
            endOffset = request.offset,
            newText = content,
        )
        return applyEditsAndValidate(
            filePath = filePath,
            edits = listOf(edit),
            query = KastWriteAndValidateInsertAtOffsetQuery(
                workspaceRoot = workspaceRoot,
                filePath = request.filePath,
                offset = request.offset,
            ),
        )
    }

    private suspend fun writeAndValidateReplace(request: KastWriteAndValidateReplaceRangeRequest): KastWriteAndValidateResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val filePath = request.filePath.normalizedAbsolutePath()
        val content = resolveContent(request.content, request.contentFile)
        val edit = TextEdit(
            filePath = filePath,
            startOffset = request.startOffset,
            endOffset = request.endOffset,
            newText = content,
        )
        return applyEditsAndValidate(
            filePath = filePath,
            edits = listOf(edit),
            query = KastWriteAndValidateReplaceRangeQuery(
                workspaceRoot = workspaceRoot,
                filePath = request.filePath,
                startOffset = request.startOffset,
                endOffset = request.endOffset,
            ),
        )
    }

    private suspend fun applyEditsAndValidate(
        filePath: String,
        edits: List<TextEdit>,
        query: KastWriteAndValidateQuery,
    ): KastWriteAndValidateResponse {
        requireMutationCapability(MutationCapability.APPLY_EDITS)
        val applyResult = backend.applyEdits(ApplyEditsQuery(edits = edits, fileHashes = emptyList()).parsed())
        val optimized = optimizeImports(filePath)
        val diagnostics = validateFiles(listOf(filePath))
        return KastWriteAndValidateSuccessResponse(
            ok = diagnostics.clean,
            query = query,
            appliedEdits = applyResult.applied.size,
            importChanges = optimized.affectedFiles.size,
            diagnostics = diagnostics,
            logFile = placeholderLogFile(),
        )
    }

    private suspend fun optimizeImports(filePath: String) = run {
        requireMutationCapability(MutationCapability.OPTIMIZE_IMPORTS)
        backend.optimizeImports(ImportOptimizeQuery(filePaths = listOf(filePath)).parsed())
    }

    private suspend fun validateFiles(filePaths: List<String>): KastDiagnosticsSummary {
        requireReadCapability(ReadCapability.DIAGNOSTICS)
        return diagnosticsSummary(
            backend.diagnostics(DiagnosticsQuery(filePaths = filePaths).parsed()).withLimit(config.maxResults, ::diagnosticPageToken),
        )
    }

    private suspend fun resolveNamedSymbol(
        symbolName: String,
        fileHint: String? = null,
        kind: WrapperNamedSymbolKind? = null,
        containingType: String? = null,
    ): ResolvedNamedSymbol? {
        val candidates = rankedNamedSymbolCandidates(
            symbolName = symbolName,
            fileHint = fileHint,
            kind = kind,
            containingType = containingType,
            line = null,
            codeSnippet = null,
            includeDeclarationScope = false,
            searchLimit = minOf(config.maxResults, DEFAULT_DISCOVERY_SEARCH_LIMIT),
        )
        val best = candidates.firstOrNull() ?: return null
        val resolved = resolveNamedSymbol(
            candidate = best,
            includeDeclarationScope = false,
            includeDocumentation = false,
        ) ?: return null
        val alternativeFqNames = candidates
            .asSequence()
            .map { it.symbol.fqName }
            .filter { it != best.symbol.fqName }
            .distinct()
            .take(3)
            .toList()
        return resolved.copy(
            candidateCount = candidates.size,
            alternativeFqNames = alternativeFqNames,
        )
    }

    private suspend fun resolveNamedSymbol(
        candidate: RankedNamedSymbolCandidate,
        includeDeclarationScope: Boolean,
        includeDocumentation: Boolean,
    ): ResolvedNamedSymbol? {
        requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
        val resolved = backend.resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = candidate.symbol.location.filePath,
                    offset = candidate.symbol.location.startOffset,
                ),
                includeDeclarationScope = includeDeclarationScope,
                includeDocumentation = includeDocumentation,
            ).parsed(),
        )
        return ResolvedNamedSymbol(
            symbol = resolved.symbol,
            filePath = candidate.symbol.location.filePath,
            offset = candidate.symbol.location.startOffset,
            candidateCount = 1,
            alternativeFqNames = emptyList(),
        )
    }

    private suspend fun rankedNamedSymbolCandidates(
        symbolName: String,
        fileHint: String?,
        kind: WrapperNamedSymbolKind?,
        containingType: String?,
        line: Int?,
        codeSnippet: String?,
        includeDeclarationScope: Boolean,
        searchLimit: Int,
    ): List<RankedNamedSymbolCandidate> {
        requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
        val result = backend.workspaceSymbolSearch(
            WorkspaceSymbolQuery(
                pattern = symbolName,
                maxResults = searchLimit,
                includeDeclarationScope = includeDeclarationScope,
            ).parsed(),
        ).withLimit(searchLimit) { workspaceSymbolPageToken(searchLimit) }

        return result.symbols
            .asSequence()
            .map { candidate ->
                rankCandidate(
                    candidate = candidate,
                    requestedSymbol = symbolName,
                    fileHint = fileHint,
                    kind = kind,
                    containingType = containingType,
                    line = line,
                    codeSnippet = codeSnippet,
                )
            }
            .sortedWith(
                compareByDescending<RankedNamedSymbolCandidate> { it.score }
                    .thenBy { it.symbol.location.filePath }
                    .thenBy { it.symbol.location.startLine }
                    .thenBy { it.symbol.fqName },
            )
            .toList()
    }

    private fun rankCandidate(
        candidate: Symbol,
        requestedSymbol: String,
        fileHint: String?,
        kind: WrapperNamedSymbolKind?,
        containingType: String?,
        line: Int?,
        codeSnippet: String?,
    ): RankedNamedSymbolCandidate {
        var score = 20
        val reasons = mutableListOf<String>()
        val simpleName = candidate.fqName.substringAfterLast('.')
        if (simpleName == requestedSymbol) {
            score += 35
            reasons += "exact simple-name match"
        } else if (simpleName.contains(requestedSymbol, ignoreCase = true)) {
            score += 10
            reasons += "simple name contains query"
        }

        if (kind != null && candidate.kind == kind.toSymbolKind()) {
            score += 15
            reasons += "kind matches ${kind.name.lowercase()}"
        } else if (kind != null) {
            score -= 30
        }

        if (!containingType.isNullOrBlank() && candidate.containingDeclaration?.endsWith(containingType) == true) {
            score += 15
            reasons += "containing declaration matches hint"
        }

        val fileHintMatches = !fileHint.isNullOrBlank() && candidate.location.filePath.endsWith(fileHint.removePrefix("/"))
        if (fileHintMatches) {
            score += 15
            reasons += "file matches hint"
        }

        if (line != null && (fileHint.isNullOrBlank() || fileHintMatches)) {
            val distance = kotlin.math.abs(candidate.location.startLine - line)
            val lineScore = when {
                distance == 0 -> 10
                distance <= 2 -> 7
                distance <= 5 -> 4
                else -> 0
            }
            if (lineScore > 0) {
                score += lineScore
                reasons += "line is $distance away"
            }
        }

        val snippetOverlap = snippetOverlap(codeSnippet, candidate)
        if (snippetOverlap > 0) {
            val snippetScore = minOf(10, snippetOverlap * 2)
            score += snippetScore
            reasons += "snippet overlaps $snippetOverlap token(s)"
        }

        return RankedNamedSymbolCandidate(
            symbol = candidate,
            score = score.coerceAtMost(100),
            reasons = reasons.ifEmpty { listOf("matched workspace symbol search") },
        )
    }

    private fun snippetOverlap(codeSnippet: String?, candidate: Symbol): Int {
        val queryTokens = codeSnippet?.tokens().orEmpty()
        if (queryTokens.isEmpty()) return 0
        val candidateTokens = listOf(
            candidate.fqName,
            candidate.location.preview,
            candidate.location.filePath,
            candidate.containingDeclaration.orEmpty(),
        ).joinToString(" ").tokens()
        return queryTokens.intersect(candidateTokens).size
    }

    private fun RankedNamedSymbolCandidate.toDiscoveryCandidate(
        rank: Int,
        workspaceRoot: String,
        requestedSymbol: String,
    ): KastDiscoveryCandidate {
        val params = KastResolveParams(
            workspaceRoot = workspaceRoot,
            symbol = requestedSymbol,
            fileHint = symbol.location.filePath,
            kind = symbol.kind.toWrapperNamedSymbolKindOrNull(),
            containingType = symbol.containingDeclaration,
        )
        return KastDiscoveryCandidate(
            rank = rank,
            confidence = score / 100.0,
            symbol = symbol,
            reasons = reasons,
            resolveParams = params,
            nextRequest = KastNextRequest(
                method = "symbol/resolve",
                params = params,
            ),
        )
    }

    private suspend fun resolveContext(
        symbol: Symbol,
        request: KastResolveRequest,
    ): KastResolveContext? {
        val surroundingText = request.surroundingLines?.let { lines ->
            sourceTextWindow(symbol, lines)
        }
        val surroundingMembers = if (request.includeSurroundingMembers) {
            surroundingMembers(symbol)
        } else {
            null
        }
        return if (surroundingText == null && surroundingMembers == null) {
            null
        } else {
            KastResolveContext(
                surroundingText = surroundingText,
                surroundingMembers = surroundingMembers,
            )
        }
    }

    private fun sourceTextWindow(symbol: Symbol, surroundingLines: Int): KastSourceTextWindow? {
        val path = Path.of(symbol.location.filePath)
        if (!Files.exists(path)) return null
        val lines = Files.readString(path).lines()
        if (lines.isEmpty()) return null
        val declarationStartLine = symbol.declarationScope?.startLine ?: symbol.location.startLine
        val declarationEndLine = symbol.declarationScope?.endLine ?: symbol.location.startLine
        val startLine = (declarationStartLine - surroundingLines).coerceAtLeast(1)
        val endLine = (declarationEndLine + surroundingLines).coerceAtMost(lines.size)
        return KastSourceTextWindow(
            filePath = symbol.location.filePath,
            startLine = startLine,
            endLine = endLine,
            text = lines.subList(startLine - 1, endLine).joinToString("\n"),
        )
    }

    private suspend fun surroundingMembers(symbol: Symbol): List<Symbol> {
        requireReadCapability(ReadCapability.FILE_OUTLINE)
        val outline = backend.fileOutline(FileOutlineQuery(filePath = symbol.location.filePath).parsed())
        return outline.symbols
            .flatMap(OutlineSymbol::flatten)
            .filter { candidate ->
                candidate.location.filePath == symbol.location.filePath &&
                    candidate.fqName != symbol.fqName &&
                    candidate.containingDeclaration == symbol.containingDeclaration
            }
            .map(Symbol::withoutHeavyContext)
            .sortedWith(compareBy({ it.location.startLine }, { it.fqName }))
    }

    private fun validateResolveQuery(query: KastResolveQuery) {
        if (query.symbol.isBlank()) {
            throw ValidationException("symbol must not be blank")
        }
        val surroundingLines = query.surroundingLines ?: return
        if (surroundingLines < 0 || surroundingLines > MAX_SURROUNDING_LINES) {
            throw ValidationException("surroundingLines must be between 0 and $MAX_SURROUNDING_LINES")
        }
    }

    private fun validateDiscoverQuery(query: KastDiscoverQuery) {
        if (query.symbol.isBlank()) {
            throw ValidationException("symbol must not be blank")
        }
        if (query.maxResults <= 0) {
            throw ValidationException("maxResults must be greater than 0")
        }
        if (query.maxResults > config.maxResults) {
            throw ValidationException("maxResults must be less than or equal to server maxResults (${config.maxResults})")
        }
        val line = query.line
        if (line != null && line <= 0) {
            throw ValidationException("line must be greater than 0")
        }
    }

    private suspend fun workspaceRootFor(explicit: String?): String =
        explicit?.takeIf(String::isNotBlank)?.normalizedAbsolutePath() ?: backend.runtimeStatus().workspaceRoot

    private suspend fun requireReadCapability(capability: ReadCapability) {
        val capabilities = backend.capabilities()
        if (!capabilities.readCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "The backend does not advertise $capability",
            )
        }
    }

    private suspend fun requireMutationCapability(capability: MutationCapability) {
        val capabilities = backend.capabilities()
        if (!capabilities.mutationCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "The backend does not advertise $capability",
            )
        }
    }

    private fun diagnosticsSummary(result: io.github.amichne.kast.api.contract.result.DiagnosticsResult): KastDiagnosticsSummary =
        KastDiagnosticsSummary(
            clean = result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
            errorCount = result.diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
            warningCount = result.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
            errors = result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR },
        )

    private fun resolveContent(content: String?, contentFile: String?): String {
        if (content != null) {
            return content
        }
        if (contentFile != null) {
            val path = Path.of(contentFile)
            if (!Files.exists(path)) {
                throw ValidationException("contentFile does not exist: $contentFile")
            }
            return Files.readString(path)
        }
        throw ValidationException("Either 'content' or 'contentFile' must be provided")
    }

    private data class ResolvedNamedSymbol(
        val symbol: Symbol,
        val filePath: String,
        val offset: Int,
        val candidateCount: Int,
        val alternativeFqNames: List<String>,
    )

    private data class RankedNamedSymbolCandidate(
        val symbol: Symbol,
        val score: Int,
        val reasons: List<String>,
    )

    private data class SymbolQueryEngineResults(
        val declarations: List<StoreSymbolQueryDeclarationMatch>,
        val graphEdgesByDeclarationId: Map<Long, List<StoreSymbolQueryGraphEdge>>,
    )

    private sealed interface SymbolQueryAnchorResolution {
        data class Success(val declaration: StoreSymbolQueryDeclarationMatch) : SymbolQueryAnchorResolution

        data class Failure(
            val reason: SymbolQueryFailureReason,
            val message: String,
        ) : SymbolQueryAnchorResolution
    }
}

private fun String.normalizedAbsolutePath(): String = Path.of(this).toAbsolutePath().normalize().toString()

private fun String.readTextIfPresent(): String? {
    val path = Path.of(this)
    return if (Files.exists(path)) Files.readString(path) else null
}

private fun WrapperNamedSymbolKind.toSymbolKind(): SymbolKind = when (this) {
    WrapperNamedSymbolKind.CLASS -> SymbolKind.CLASS
    WrapperNamedSymbolKind.INTERFACE -> SymbolKind.INTERFACE
    WrapperNamedSymbolKind.OBJECT -> SymbolKind.OBJECT
    WrapperNamedSymbolKind.FUNCTION -> SymbolKind.FUNCTION
    WrapperNamedSymbolKind.PROPERTY -> SymbolKind.PROPERTY
}

private fun SymbolKind.toWrapperNamedSymbolKindOrNull(): WrapperNamedSymbolKind? = when (this) {
    SymbolKind.CLASS -> WrapperNamedSymbolKind.CLASS
    SymbolKind.INTERFACE -> WrapperNamedSymbolKind.INTERFACE
    SymbolKind.OBJECT -> WrapperNamedSymbolKind.OBJECT
    SymbolKind.FUNCTION -> WrapperNamedSymbolKind.FUNCTION
    SymbolKind.PROPERTY -> WrapperNamedSymbolKind.PROPERTY
    else -> null
}

private fun OutlineSymbol.flatten(): List<Symbol> =
    listOf(symbol) + children.flatMap(OutlineSymbol::flatten)

private fun Symbol.withoutHeavyContext(): Symbol = copy(
    declarationScope = null,
)

private fun String.tokens(): Set<String> =
    lowercase()
        .split(Regex("[^a-z0-9_]+"))
        .filter { it.length >= 2 }
        .toSet()

private fun WrapperCallDirection.toCallDirection(): CallDirection = when (this) {
    WrapperCallDirection.INCOMING -> CallDirection.INCOMING
    WrapperCallDirection.OUTGOING -> CallDirection.OUTGOING
}

private fun WrapperScaffoldMode.toInsertionTarget(): SemanticInsertionTarget = when (this) {
    WrapperScaffoldMode.IMPLEMENT -> SemanticInsertionTarget.CLASS_BODY_END
    WrapperScaffoldMode.REPLACE -> SemanticInsertionTarget.CLASS_BODY_START
    WrapperScaffoldMode.CONSOLIDATE -> SemanticInsertionTarget.FILE_BOTTOM
    WrapperScaffoldMode.EXTRACT -> SemanticInsertionTarget.AFTER_IMPORTS
}

private fun KastSymbolQueryAnchor?.hasAnchorValue(): Boolean =
    this != null && listOf(fqName, symbol, filePath).any { !it.isNullOrBlank() }

private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun placeholderLogFile(): String = "/dev/null"

private fun referencePageToken(location: Location): String = location.startOffset.toString()

private fun diagnosticPageToken(diagnostic: Diagnostic): String = diagnostic.location.startOffset.toString()

private fun workspaceSymbolPageToken(limit: Int): String = limit.toString()

@Suppress("UNCHECKED_CAST")
private fun <T, R : PageableResult<T>> R.withLimit(
    limit: Int,
    nextPageToken: (T) -> String,
): R {
    if (items.size <= limit) {
        return this
    }
    return withItems(
        items = items.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = nextPageToken(items[limit - 1]),
        ),
    ) as R
}

private fun <T> Json.encodeList(serializer: kotlinx.serialization.KSerializer<T>, items: List<T>): JsonElement =
    encodeToJsonElement(ListSerializer(serializer), items)
