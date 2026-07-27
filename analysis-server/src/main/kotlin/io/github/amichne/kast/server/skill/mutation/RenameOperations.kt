package io.github.amichne.kast.server.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun SkillRpcContext.rename(request: KastRenameRequest): KastRenameResponse = when (request) {
    is KastRenameBySymbolRequest -> renameBySymbol(request)
    is KastRenameByOffsetRequest -> renameByOffset(request)
    is KastRenameBySelectorHandleRequest -> renameBySelectorHandle(request)
}

internal suspend fun SkillRpcContext.writeAndValidate(request: KastWriteAndValidateRequest): KastWriteAndValidateResponse = when (request) {
    is KastWriteAndValidateCreateFileRequest -> writeAndValidateCreate(request)
    is KastWriteAndValidateInsertAtOffsetRequest -> writeAndValidateInsert(request)
    is KastWriteAndValidateReplaceRangeRequest -> writeAndValidateReplace(request)
}

internal suspend fun SkillRpcContext.addFile(request: KastAddFileRequest): KastScopeMutationResponse {
    val filePath = request.targetFilePath.value
    return writeAndValidateCreate(
        KastWriteAndValidateCreateFileRequest(
            workspaceRoot = request.requestedWorkspaceRoot?.value,
            filePath = filePath,
            contentFile = request.contentFilePath.value,
        ),
    ).toScopeMutationResponse(
        operation = request.operation,
        affectedFiles = listOf(filePath),
        createdFiles = listOf(filePath),
        editCount = 1,
    )
}

internal suspend fun SkillRpcContext.addDeclaration(request: KastAddDeclarationRequest): KastScopeMutationResponse = addPlacedContent(request)

internal suspend fun SkillRpcContext.addImplementation(request: KastAddImplementationRequest): KastScopeMutationResponse = addPlacedContent(request)

internal suspend fun SkillRpcContext.addStatement(request: KastAddStatementRequest): KastScopeMutationResponse {
    val placement = KastPlacementSelector(
        scope = KastNamedPlacementScope(
            insideScope = request.requestedInsideScope.value,
            kind = WrapperNamedSymbolKind.FUNCTION,
        ),
        anchor = KastAtPlacementAnchor(request.anchor.toPlacementAnchor()),
    )
    return addContentAtPlacement(
        operation = request.operation,
        workspaceRoot = request.requestedWorkspaceRoot?.value,
        placement = placement,
        contentFile = request.contentFilePath.value,
        statementBody = true,
    )
}

internal suspend fun SkillRpcContext.replaceDeclaration(request: KastReplaceDeclarationRequest): KastScopeMutationResponse = when (request) {
    is KastReplaceDeclarationBySymbolRequest -> replaceDeclarationBySymbol(request)
    is KastReplaceDeclarationBySelectorHandleRequest -> replaceDeclarationBySelectorHandle(request)
}

internal suspend fun SkillRpcContext.replaceDeclarationBySymbol(
    request: KastReplaceDeclarationBySymbolRequest,
): KastScopeMutationResponse {
    val workspaceRoot = workspaceRootFor(request.requestedWorkspaceRoot?.value)
    val symbol = request.requestedSymbol.value
    val resolved = resolveNamedSymbol(
        symbolName = symbol,
        fileHint = request.fileHint,
        kind = request.kind,
        containingType = request.containingType,
        includeDeclarationScope = true,
    ) ?: return KastScopeMutationFailureResponse(
        operation = request.operation,
        stage = "resolve",
        message = "No symbol matching '$symbol' found in workspace",
        logFile = placeholderLogFile(),
    )
    return replaceResolvedDeclaration(
        operation = request.operation,
        workspaceRoot = workspaceRoot,
        contentFile = request.contentFilePath.value,
        subject = symbol,
        filePath = resolved.filePath,
        symbol = resolved.symbol,
    )
}

internal suspend fun SkillRpcContext.replaceDeclarationBySelectorHandle(
    request: KastReplaceDeclarationBySelectorHandleRequest,
): KastScopeMutationResponse {
    val workspaceRoot = workspaceRootFor(request.requestedWorkspaceRoot?.value)
    val selected = when (
        val selection = selectSelector(
            explicitSelector = null,
            selectorHandle = request.selectorHandle,
            workspaceRoot = workspaceRoot,
            family = SelectorOperationFamily.REPLACE_DECLARATION,
        )
    ) {
        is SelectorSelection.Rejected ->
            return KastSelectorHandleRejectedResponse(selection.reason)
        is SelectorSelection.Selected -> selection
    }
    val selector = selected.selector
    requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
    val resolved = try {
        backend.resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = selector.declarationFile,
                    offset = selector.declarationStartOffset,
                ),
                includeDeclarationScope = true,
            ).parsed(),
        ).symbol
    } catch (_: NotFoundException) {
        return KastScopeMutationFailureResponse(
            operation = request.operation,
            stage = "resolve",
            message = "Selector handle declaration no longer exists",
            logFile = placeholderLogFile(),
        )
    }
    if (!selector.matches(resolved.toSymbolIdentity())) {
        return KastScopeMutationFailureResponse(
            operation = request.operation,
            stage = "resolve",
            message = "Selector handle declaration identity no longer matches the compiler subject",
            logFile = placeholderLogFile(),
        )
    }
    return replaceResolvedDeclaration(
        operation = request.operation,
        workspaceRoot = workspaceRoot,
        contentFile = request.contentFilePath.value,
        subject = selector.fqName,
        filePath = selector.declarationFile,
        symbol = resolved,
    )
}

internal suspend fun SkillRpcContext.replaceResolvedDeclaration(
    operation: KastScopeMutationOperation,
    workspaceRoot: String,
    contentFile: String,
    subject: String,
    filePath: String,
    symbol: Symbol,
): KastScopeMutationResponse {
    val declarationScope = symbol.declarationScope ?: return KastScopeMutationFailureResponse(
        operation = operation,
        stage = "resolve",
        message = "Resolved symbol '$subject' did not include declaration scope",
        logFile = placeholderLogFile(),
    )
    val content = resolveContent(null, contentFile)
    val response = applyEditsAndValidate(
        filePath = filePath,
        edits = listOf(
            TextEdit(
                filePath = filePath,
                startOffset = declarationScope.startOffset,
                endOffset = declarationScope.endOffset,
                newText = content,
            ),
        ),
        query = KastWriteAndValidateReplaceRangeQuery(
            workspaceRoot = workspaceRoot,
            filePath = filePath,
            startOffset = declarationScope.startOffset,
            endOffset = declarationScope.endOffset,
        ),
    )
    return response.toScopeMutationResponse(
        operation = operation,
        affectedFiles = listOf(filePath),
        editCount = 1,
    )
}

internal suspend fun SkillRpcContext.renameBySymbol(request: KastRenameBySymbolRequest): KastRenameResponse {
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

internal suspend fun SkillRpcContext.renameByOffset(request: KastRenameByOffsetRequest): KastRenameResponse {
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

internal suspend fun SkillRpcContext.renameBySelectorHandle(request: KastRenameBySelectorHandleRequest): KastRenameResponse {
    val workspaceRoot = workspaceRootFor(request.workspaceRoot)
    val selected = when (
        val selection = selectSelector(
            explicitSelector = null,
            selectorHandle = request.selectorHandle,
            workspaceRoot = workspaceRoot,
            family = SelectorOperationFamily.RENAME,
        )
    ) {
        is SelectorSelection.Rejected ->
            return KastSelectorHandleRejectedResponse(selection.reason)
        is SelectorSelection.Selected -> selection
    }
    val selector = selected.selector
    return performRename(
        filePath = selector.declarationFile,
        offset = selector.declarationStartOffset,
        newName = request.newName,
        queryBuilder = {
            KastRenameBySelectorHandleQuery(
                workspaceRoot = workspaceRoot,
                selectorHandle = request.selectorHandle,
                newName = request.newName,
                filePath = selector.declarationFile,
                offset = selector.declarationStartOffset,
            )
        },
        failureQueryBuilder = {
            KastRenameFailureQuery(
                type = "RENAME_BY_SELECTOR_HANDLE_REQUEST",
                workspaceRoot = workspaceRoot,
                filePath = selector.declarationFile,
                offset = selector.declarationStartOffset,
                newName = request.newName,
            )
        },
    )
}

internal suspend fun SkillRpcContext.performRename(
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
    requireCapabilities(
        readCapabilities = if (renameResult.affectedFiles.isEmpty()) {
            emptySet()
        } else {
            setOf(ReadCapability.DIAGNOSTICS)
        },
        mutationCapabilities = buildSet {
            add(MutationCapability.APPLY_EDITS)
            if (renameResult.affectedFiles.isNotEmpty()) {
                add(MutationCapability.REFRESH_WORKSPACE)
            }
        },
    )
    val applyResult = backend.applyEdits(
        ApplyEditsQuery(
            edits = renameResult.edits,
            fileHashes = renameResult.fileHashes,
        ).parsed(),
    )
    currentCoroutineContext().ensureActive()
    val diagnosticsSummary = if (renameResult.affectedFiles.isEmpty()) {
        KastDiagnosticsSummary.completeWithoutFiles()
    } else {
        val admission = awaitSemanticAdmission(renameResult.affectedFiles)
        if (admission.clean) {
            validateFiles(renameResult.affectedFiles)
        } else {
            admission
        }
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
