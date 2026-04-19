package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.FileOutlineQuery
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.KastCallersFailureResponse
import io.github.amichne.kast.api.KastCallersQuery
import io.github.amichne.kast.api.KastCallersRequest
import io.github.amichne.kast.api.KastCallersSuccessResponse
import io.github.amichne.kast.api.KastDiagnosticsQuery
import io.github.amichne.kast.api.KastDiagnosticsRequest
import io.github.amichne.kast.api.KastDiagnosticsSuccessResponse
import io.github.amichne.kast.api.KastDiagnosticsSummary
import io.github.amichne.kast.api.KastReferencesFailureResponse
import io.github.amichne.kast.api.KastReferencesQuery
import io.github.amichne.kast.api.KastReferencesRequest
import io.github.amichne.kast.api.KastReferencesSuccessResponse
import io.github.amichne.kast.api.KastRenameByOffsetQuery
import io.github.amichne.kast.api.KastRenameByOffsetRequest
import io.github.amichne.kast.api.KastRenameBySymbolQuery
import io.github.amichne.kast.api.KastRenameBySymbolRequest
import io.github.amichne.kast.api.KastRenameFailureQuery
import io.github.amichne.kast.api.KastRenameFailureResponse
import io.github.amichne.kast.api.KastRenameRequest
import io.github.amichne.kast.api.KastRenameSuccessResponse
import io.github.amichne.kast.api.KastResolveFailureResponse
import io.github.amichne.kast.api.KastResolveQuery
import io.github.amichne.kast.api.KastResolveRequest
import io.github.amichne.kast.api.KastResolveSuccessResponse
import io.github.amichne.kast.api.KastScaffoldQuery
import io.github.amichne.kast.api.KastScaffoldRequest
import io.github.amichne.kast.api.KastScaffoldSuccessResponse
import io.github.amichne.kast.api.KastWorkspaceFilesQuery
import io.github.amichne.kast.api.KastWorkspaceFilesRequest
import io.github.amichne.kast.api.KastWorkspaceFilesSuccessResponse
import io.github.amichne.kast.api.KastWriteAndValidateCreateFileQuery
import io.github.amichne.kast.api.KastWriteAndValidateCreateFileRequest
import io.github.amichne.kast.api.KastWriteAndValidateFailureQuery
import io.github.amichne.kast.api.KastWriteAndValidateFailureResponse
import io.github.amichne.kast.api.KastWriteAndValidateInsertAtOffsetQuery
import io.github.amichne.kast.api.KastWriteAndValidateInsertAtOffsetRequest
import io.github.amichne.kast.api.KastWriteAndValidateReplaceRangeQuery
import io.github.amichne.kast.api.KastWriteAndValidateReplaceRangeRequest
import io.github.amichne.kast.api.KastWriteAndValidateRequest
import io.github.amichne.kast.api.KastWriteAndValidateSuccessResponse
import io.github.amichne.kast.api.KastCandidate
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.SemanticInsertionQuery
import io.github.amichne.kast.api.SemanticInsertionTarget
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.TextEdit
import io.github.amichne.kast.api.TypeHierarchyQuery
import io.github.amichne.kast.api.WrapperCallDirection
import io.github.amichne.kast.api.WorkspaceFilesQuery
import io.github.amichne.kast.cli.CliCommand
import io.github.amichne.kast.cli.CliFailure
import io.github.amichne.kast.cli.CliService
import io.github.amichne.kast.cli.RuntimeCommandOptions
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Executes skill wrapper commands by deserializing the request,
 * calling [CliService] methods, and producing wrapper response objects.
 */
internal class SkillWrapperExecutor(
    private val cliService: CliService,
    private val json: Json,
) {
    private val symbolResolver = NamedSymbolResolver(cliService)

    suspend fun execute(command: CliCommand.Skill): Any {
        val rawJson = SkillWrapperInput.parseJsonInput(command.rawInput)
        return when (command.name) {
            SkillWrapperName.WORKSPACE_FILES -> executeWorkspaceFiles(rawJson)
            SkillWrapperName.DIAGNOSTICS -> executeDiagnostics(rawJson)
            SkillWrapperName.RESOLVE -> executeResolve(rawJson)
            SkillWrapperName.REFERENCES -> executeReferences(rawJson)
            SkillWrapperName.CALLERS -> executeCallers(rawJson)
            SkillWrapperName.RENAME -> executeRename(rawJson)
            SkillWrapperName.SCAFFOLD -> executeScaffold(rawJson)
            SkillWrapperName.WRITE_AND_VALIDATE -> executeWriteAndValidate(rawJson)
        }
    }

    // region workspace-files

    private suspend fun executeWorkspaceFiles(rawJson: String): Any {
        val request = json.decodeFromString<KastWorkspaceFilesRequest>(rawJson)
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val query = WorkspaceFilesQuery(
            moduleName = request.moduleName,
            includeFiles = request.includeFiles,
        )
        val result = cliService.workspaceFiles(options, query)
        return KastWorkspaceFilesSuccessResponse(
            ok = true,
            query = KastWorkspaceFilesQuery(
                workspaceRoot = workspaceRoot,
                moduleName = request.moduleName,
                includeFiles = request.includeFiles,
            ),
            modules = result.payload.modules,
            schemaVersion = result.payload.schemaVersion,
            logFile = SkillLogFile.placeholder(),
        )
    }

    // endregion

    // region diagnostics

    private suspend fun executeDiagnostics(rawJson: String): Any {
        val request = json.decodeFromString<KastDiagnosticsRequest>(rawJson)
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val filePaths = request.filePaths.map { path ->
            Path.of(path).toAbsolutePath().normalize().toString()
        }
        val diagnosticsResult = cliService.diagnostics(options, DiagnosticsQuery(filePaths = filePaths)).payload
        return KastDiagnosticsSuccessResponse(
            ok = true,
            query = KastDiagnosticsQuery(
                workspaceRoot = workspaceRoot,
                filePaths = request.filePaths,
            ),
            clean = diagnosticsResult.diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
            errorCount = diagnosticsResult.diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
            warningCount = diagnosticsResult.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
            infoCount = diagnosticsResult.diagnostics.count { it.severity == DiagnosticSeverity.INFO },
            diagnostics = diagnosticsResult.diagnostics,
            logFile = SkillLogFile.placeholder(),
        )
    }

    // endregion

    // region resolve

    private suspend fun executeResolve(rawJson: String): Any {
        val request = json.decodeFromString<KastResolveRequest>(rawJson)
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val query = KastResolveQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        )

        val resolved = symbolResolver.resolve(
            options = options,
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        ) ?: return KastResolveFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = SkillLogFile.placeholder(),
        )

        return KastResolveSuccessResponse(
            ok = true,
            query = query,
            symbol = resolved.symbol,
            filePath = resolved.filePath,
            offset = resolved.offset,
            candidate = KastCandidate(
                line = resolved.symbol.location.startLine,
                column = resolved.symbol.location.startColumn,
                context = resolved.symbol.location.preview,
            ),
            logFile = SkillLogFile.placeholder(),
        )
    }

    // endregion

    // region references

    private suspend fun executeReferences(rawJson: String): Any {
        val request = json.decodeFromString<KastReferencesRequest>(rawJson)
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val query = KastReferencesQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            includeDeclaration = request.includeDeclaration,
        )

        val resolved = symbolResolver.resolve(
            options = options,
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        ) ?: return KastReferencesFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = SkillLogFile.placeholder(),
        )

        val refsResult = cliService.findReferences(
            options,
            ReferencesQuery(
                position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                includeDeclaration = request.includeDeclaration,
            ),
        ).payload

        return KastReferencesSuccessResponse(
            ok = true,
            query = query,
            symbol = resolved.symbol,
            filePath = resolved.filePath,
            offset = resolved.offset,
            references = refsResult.references,
            searchScope = refsResult.searchScope,
            declaration = refsResult.declaration,
            logFile = SkillLogFile.placeholder(),
        )
    }

    // endregion

    // region callers

    private suspend fun executeCallers(rawJson: String): Any {
        val request = json.decodeFromString<KastCallersRequest>(rawJson)
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
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

        val resolved = symbolResolver.resolve(
            options = options,
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
        ) ?: return KastCallersFailureResponse(
            stage = "resolve",
            message = "No symbol matching '${request.symbol}' found in workspace",
            query = query,
            logFile = SkillLogFile.placeholder(),
        )

        val direction = when (request.direction) {
            WrapperCallDirection.INCOMING -> CallDirection.INCOMING
            WrapperCallDirection.OUTGOING -> CallDirection.OUTGOING
        }

        val hierarchyResult = cliService.callHierarchy(
            options,
            CallHierarchyQuery(
                position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                direction = direction,
                depth = request.depth,
                maxTotalCalls = request.maxTotalCalls ?: 256,
                maxChildrenPerNode = request.maxChildrenPerNode ?: 64,
                timeoutMillis = request.timeoutMillis?.toLong(),
            ),
        ).payload

        return KastCallersSuccessResponse(
            ok = true,
            query = query,
            symbol = resolved.symbol,
            filePath = resolved.filePath,
            offset = resolved.offset,
            root = hierarchyResult.root,
            stats = hierarchyResult.stats,
            logFile = SkillLogFile.placeholder(),
        )
    }

    // endregion

    // region rename

    private suspend fun executeRename(rawJson: String): Any {
        val request = json.decodeFromString<KastRenameRequest>(rawJson)
        return when (request) {
            is KastRenameBySymbolRequest -> executeRenameBySymbol(request)
            is KastRenameByOffsetRequest -> executeRenameByOffset(request)
        }
    }

    private suspend fun executeRenameBySymbol(request: KastRenameBySymbolRequest): Any {
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)

        val resolved = symbolResolver.resolve(
            options = options,
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
            logFile = SkillLogFile.placeholder(),
        )

        return performRename(
            options = options,
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

    private suspend fun executeRenameByOffset(request: KastRenameByOffsetRequest): Any {
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val filePath = Path.of(request.filePath).toAbsolutePath().normalize().toString()

        return performRename(
            options = options,
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
        options: RuntimeCommandOptions,
        filePath: String,
        offset: Int,
        newName: String,
        queryBuilder: () -> io.github.amichne.kast.api.KastRenameQuery,
        failureQueryBuilder: () -> KastRenameFailureQuery,
    ): Any {
        // Dry-run rename to get edits
        val renameResult = cliService.rename(
            options,
            RenameQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                newName = newName,
                dryRun = true,
            ),
        ).payload

        // Apply the edits
        val applyResult = cliService.applyEdits(
            options,
            ApplyEditsQuery(
                edits = renameResult.edits,
                fileHashes = renameResult.fileHashes,
            ),
        ).payload

        // Run diagnostics on affected files
        val affectedFiles = renameResult.affectedFiles
        val diagnosticsPayload = if (affectedFiles.isNotEmpty()) {
            cliService.diagnostics(options, DiagnosticsQuery(filePaths = affectedFiles)).payload
        } else {
            null
        }
        val diagSummary = diagnosticsPayload?.let { d ->
            KastDiagnosticsSummary(
                clean = d.diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
                errorCount = d.diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
                warningCount = d.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
                errors = d.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR },
            )
        } ?: KastDiagnosticsSummary(clean = true, errorCount = 0, warningCount = 0)

        return KastRenameSuccessResponse(
            ok = diagSummary.clean,
            query = queryBuilder(),
            editCount = renameResult.edits.size,
            affectedFiles = renameResult.affectedFiles,
            applyResult = applyResult,
            diagnostics = diagSummary,
            logFile = SkillLogFile.placeholder(),
        )
    }

    // endregion

    // region scaffold

    private suspend fun executeScaffold(rawJson: String): Any {
        val request = json.decodeFromString<KastScaffoldRequest>(rawJson)
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val targetFile = Path.of(request.targetFile).toAbsolutePath().normalize().toString()

        // File outline
        val outlineResult = cliService.fileOutline(
            options,
            FileOutlineQuery(filePath = targetFile),
        ).payload

        // Optional: resolve target symbol if specified
        val resolvedSymbol = request.targetSymbol?.let { symbolName ->
            symbolResolver.resolve(
                options = options,
                symbolName = symbolName,
                fileHint = request.targetFile,
                kind = request.kind,
            )
        }

        // Optional: references if we have a resolved symbol
        val references = resolvedSymbol?.let { sym ->
            val refsPayload = cliService.findReferences(
                options,
                ReferencesQuery(
                    position = FilePosition(filePath = sym.filePath, offset = sym.offset),
                    includeDeclaration = true,
                ),
            ).payload
            io.github.amichne.kast.api.KastScaffoldReferences(
                locations = refsPayload.references,
                count = refsPayload.references.size,
                searchScope = refsPayload.searchScope,
                declaration = refsPayload.declaration,
            )
        }

        // Optional: type hierarchy if we have a class/interface/object symbol
        val typeHierarchy = resolvedSymbol?.takeIf {
            it.symbol.kind in setOf(
                io.github.amichne.kast.api.SymbolKind.CLASS,
                io.github.amichne.kast.api.SymbolKind.INTERFACE,
                io.github.amichne.kast.api.SymbolKind.OBJECT,
            )
        }?.let { sym ->
            val thPayload = cliService.typeHierarchy(
                options,
                TypeHierarchyQuery(
                    position = FilePosition(filePath = sym.filePath, offset = sym.offset),
                ),
            ).payload
            io.github.amichne.kast.api.KastScaffoldTypeHierarchy(
                root = thPayload.root,
                stats = thPayload.stats,
            )
        }

        // Optional: insertion point
        val insertionPoint = resolvedSymbol?.let { sym ->
            val target = when (request.mode) {
                io.github.amichne.kast.api.WrapperScaffoldMode.IMPLEMENT -> SemanticInsertionTarget.CLASS_BODY_END
                io.github.amichne.kast.api.WrapperScaffoldMode.REPLACE -> SemanticInsertionTarget.CLASS_BODY_START
                io.github.amichne.kast.api.WrapperScaffoldMode.CONSOLIDATE -> SemanticInsertionTarget.FILE_BOTTOM
                io.github.amichne.kast.api.WrapperScaffoldMode.EXTRACT -> SemanticInsertionTarget.AFTER_IMPORTS
            }
            cliService.semanticInsertionPoint(
                options,
                SemanticInsertionQuery(
                    position = FilePosition(filePath = sym.filePath, offset = sym.offset),
                    target = target,
                ),
            ).payload
        }

        // File content
        val fileContent = java.io.File(targetFile).takeIf { it.exists() }?.readText()

        return KastScaffoldSuccessResponse(
            ok = true,
            query = KastScaffoldQuery(
                workspaceRoot = workspaceRoot,
                targetFile = request.targetFile,
                targetSymbol = request.targetSymbol,
                mode = request.mode,
                kind = request.kind,
            ),
            outline = outlineResult.symbols,
            fileContent = fileContent,
            symbol = resolvedSymbol?.symbol,
            references = references,
            typeHierarchy = typeHierarchy,
            insertionPoint = insertionPoint,
            logFile = SkillLogFile.placeholder(),
        )
    }

    // endregion

    // region write-and-validate

    private suspend fun executeWriteAndValidate(rawJson: String): Any {
        val request = json.decodeFromString<KastWriteAndValidateRequest>(rawJson)
        return when (request) {
            is KastWriteAndValidateCreateFileRequest -> executeWriteAndValidateCreate(request)
            is KastWriteAndValidateInsertAtOffsetRequest -> executeWriteAndValidateInsert(request)
            is KastWriteAndValidateReplaceRangeRequest -> executeWriteAndValidateReplace(request)
        }
    }

    private suspend fun executeWriteAndValidateCreate(request: KastWriteAndValidateCreateFileRequest): Any {
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val filePath = Path.of(request.filePath).toAbsolutePath().normalize().toString()
        val content = resolveContent(request.content, request.contentFile)

        val query = KastWriteAndValidateCreateFileQuery(
            workspaceRoot = workspaceRoot,
            filePath = request.filePath,
        )

        // Apply via file operation (create)
        val applyResult = cliService.applyEdits(
            options,
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    io.github.amichne.kast.api.FileOperation.CreateFile(
                        filePath = filePath,
                        content = content,
                    ),
                ),
            ),
        ).payload

        // Optimize imports + diagnostics
        val importResult = runCatching {
            cliService.optimizeImports(
                options,
                io.github.amichne.kast.api.ImportOptimizeQuery(filePaths = listOf(filePath)),
            ).payload
        }.getOrNull()

        val diagnosticsPayload = cliService.diagnostics(
            options,
            DiagnosticsQuery(filePaths = listOf(filePath)),
        ).payload

        val diagSummary = KastDiagnosticsSummary(
            clean = diagnosticsPayload.diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
            errorCount = diagnosticsPayload.diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
            warningCount = diagnosticsPayload.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
            errors = diagnosticsPayload.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR },
        )

        return KastWriteAndValidateSuccessResponse(
            ok = diagSummary.clean,
            query = query,
            appliedEdits = applyResult.applied.size + applyResult.createdFiles.size,
            importChanges = importResult?.edits?.size ?: 0,
            diagnostics = diagSummary,
            logFile = SkillLogFile.placeholder(),
        )
    }

    private suspend fun executeWriteAndValidateInsert(request: KastWriteAndValidateInsertAtOffsetRequest): Any {
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val filePath = Path.of(request.filePath).toAbsolutePath().normalize().toString()
        val content = resolveContent(request.content, request.contentFile)

        val query = KastWriteAndValidateInsertAtOffsetQuery(
            workspaceRoot = workspaceRoot,
            filePath = request.filePath,
            offset = request.offset,
        )

        val edit = TextEdit(
            filePath = filePath,
            startOffset = request.offset,
            endOffset = request.offset,
            newText = content,
        )

        return applyEditsAndValidate(options, listOf(edit), filePath, query)
    }

    private suspend fun executeWriteAndValidateReplace(request: KastWriteAndValidateReplaceRangeRequest): Any {
        val workspaceRoot = requireWorkspaceRoot(request.workspaceRoot)
        val options = runtimeOptionsFor(workspaceRoot)
        val filePath = Path.of(request.filePath).toAbsolutePath().normalize().toString()
        val content = resolveContent(request.content, request.contentFile)

        val query = KastWriteAndValidateReplaceRangeQuery(
            workspaceRoot = workspaceRoot,
            filePath = request.filePath,
            startOffset = request.startOffset,
            endOffset = request.endOffset,
        )

        val edit = TextEdit(
            filePath = filePath,
            startOffset = request.startOffset,
            endOffset = request.endOffset,
            newText = content,
        )

        return applyEditsAndValidate(options, listOf(edit), filePath, query)
    }

    private suspend fun applyEditsAndValidate(
        options: RuntimeCommandOptions,
        edits: List<TextEdit>,
        filePath: String,
        query: io.github.amichne.kast.api.KastWriteAndValidateQuery,
    ): Any {
        val applyResult = cliService.applyEdits(
            options,
            ApplyEditsQuery(edits = edits, fileHashes = emptyList()),
        ).payload

        val importResult = runCatching {
            cliService.optimizeImports(
                options,
                io.github.amichne.kast.api.ImportOptimizeQuery(filePaths = listOf(filePath)),
            ).payload
        }.getOrNull()

        val diagnosticsPayload = cliService.diagnostics(
            options,
            DiagnosticsQuery(filePaths = listOf(filePath)),
        ).payload

        val diagSummary = KastDiagnosticsSummary(
            clean = diagnosticsPayload.diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
            errorCount = diagnosticsPayload.diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
            warningCount = diagnosticsPayload.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
            errors = diagnosticsPayload.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR },
        )

        return KastWriteAndValidateSuccessResponse(
            ok = diagSummary.clean,
            query = query,
            appliedEdits = applyResult.applied.size,
            importChanges = importResult?.affectedFiles?.size ?: 0,
            diagnostics = diagSummary,
            logFile = SkillLogFile.placeholder(),
        )
    }

    private fun resolveContent(content: String?, contentFile: String?): String {
        if (content != null) return content
        if (contentFile != null) {
            val file = java.io.File(contentFile)
            if (!file.exists()) {
                throw CliFailure(
                    code = "SKILL_VALIDATION",
                    message = "contentFile does not exist: $contentFile",
                )
            }
            return file.readText()
        }
        throw CliFailure(
            code = "SKILL_VALIDATION",
            message = "Either 'content' or 'contentFile' must be provided",
        )
    }

    // endregion

    // region shared

    private fun requireWorkspaceRoot(explicit: String?): String {
        val resolved = SkillWrapperInput.resolveWorkspaceRoot(explicit)
        if (resolved.isEmpty()) {
            throw CliFailure(
                code = "SKILL_VALIDATION",
                message = "workspaceRoot is required: set it in the request body or export KAST_WORKSPACE_ROOT.",
            )
        }
        return resolved
    }

    private fun runtimeOptionsFor(workspaceRoot: String): RuntimeCommandOptions = RuntimeCommandOptions(
        workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize(),
        backendName = null,
        waitTimeoutMillis = 60_000L,
    )

    // endregion
}
