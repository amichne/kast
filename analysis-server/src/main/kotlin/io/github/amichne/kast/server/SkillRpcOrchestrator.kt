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
import io.github.amichne.kast.api.contract.skill.WrapperNamedSymbolKind
import io.github.amichne.kast.api.contract.skill.WrapperScaffoldMode
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.parsed
import kotlinx.serialization.json.Json
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
