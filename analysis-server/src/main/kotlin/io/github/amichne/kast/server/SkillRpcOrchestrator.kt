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
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import io.github.amichne.kast.api.contract.skill.KastMetricsQuery
import io.github.amichne.kast.api.contract.skill.KastMetricsRequest
import io.github.amichne.kast.api.contract.skill.KastMetricsResponse
import io.github.amichne.kast.api.contract.skill.KastMetricsSuccessResponse
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
import io.github.amichne.kast.indexstore.metrics.MetricsEngine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path

internal class SkillRpcOrchestrator(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val json: Json,
) {
    suspend fun resolve(request: KastResolveRequest): KastResolveResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastResolveQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        )
        val resolved = resolveNamedSymbol(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        ) ?: return KastResolveFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = placeholderLogFile(),
        )
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
            candidateCount = resolved.candidateCount.takeIf { it > 1 },
            alternatives = resolved.alternativeFqNames.takeIf { it.isNotEmpty() },
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
        requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
        val searchResult = backend.workspaceSymbolSearch(
            WorkspaceSymbolQuery(pattern = symbolName, maxResults = minOf(config.maxResults, 100)).parsed(),
        ).withLimit(minOf(config.maxResults, 100)) { workspaceSymbolPageToken(minOf(config.maxResults, 100)) }
        var candidates = searchResult.symbols
        if (!fileHint.isNullOrEmpty()) {
            val hintSuffix = fileHint.removePrefix("/")
            val filtered = candidates.filter { it.location.filePath.endsWith(hintSuffix) }
            if (filtered.isNotEmpty()) {
                candidates = filtered
            }
        }
        if (kind != null) {
            val filtered = candidates.filter { it.kind == kind.toSymbolKind() }
            if (filtered.isNotEmpty()) {
                candidates = filtered
            }
        }
        if (!containingType.isNullOrEmpty()) {
            val filtered = candidates.filter { it.containingDeclaration?.endsWith(containingType) == true }
            if (filtered.isNotEmpty()) {
                candidates = filtered
            }
        }
        val exactMatches = candidates.filter { it.fqName.substringAfterLast('.') == symbolName }
        if (exactMatches.isNotEmpty()) {
            candidates = exactMatches
        }
        val best = candidates.firstOrNull() ?: return null
        val alternativeFqNames = candidates
            .asSequence()
            .map(Symbol::fqName)
            .filter { it != best.fqName }
            .distinct()
            .take(3)
            .toList()
        requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
        val resolved = backend.resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = best.location.filePath,
                    offset = best.location.startOffset,
                ),
            ).parsed(),
        )
        return ResolvedNamedSymbol(
            symbol = resolved.symbol,
            filePath = best.location.filePath,
            offset = best.location.startOffset,
            candidateCount = candidates.size,
            alternativeFqNames = alternativeFqNames,
        )
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
