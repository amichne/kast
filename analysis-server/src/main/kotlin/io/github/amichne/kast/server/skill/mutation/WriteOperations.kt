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

internal suspend fun SkillRpcContext.writeAndValidateCreate(
    request: KastWriteAndValidateCreateFileRequest,
): KastWriteAndValidateResponse {
    val workspaceRoot = workspaceRootFor(request.workspaceRoot)
    val filePath = request.filePath.normalizedAbsolutePath()
    val content = resolveContent(request.content, request.contentFile)
    requireCapabilities(
        readCapabilities = setOf(ReadCapability.DIAGNOSTICS),
        mutationCapabilities = setOf(
            MutationCapability.APPLY_EDITS,
            MutationCapability.FILE_OPERATIONS,
            MutationCapability.REFRESH_WORKSPACE,
            MutationCapability.OPTIMIZE_IMPORTS,
        ),
    )
    val applyResult = backend.applyEdits(
        ApplyEditsQuery(
            edits = emptyList(),
            fileHashes = emptyList(),
            fileOperations = listOf(FileOperation.CreateFile(filePath = filePath, content = content)),
        ).parsed(),
    )
    currentCoroutineContext().ensureActive()
    val admission = awaitSemanticAdmission(listOf(filePath))
    if (!admission.clean) {
        return KastWriteAndValidateSuccessResponse(
            ok = false,
            query = KastWriteAndValidateCreateFileQuery(
                workspaceRoot = workspaceRoot,
                filePath = request.filePath,
            ),
            appliedEdits = applyResult.applied.size + applyResult.createdFiles.size,
            importChanges = 0,
            diagnostics = admission,
            logFile = placeholderLogFile(),
        )
    }
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

internal suspend fun SkillRpcContext.writeAndValidateInsert(request: KastWriteAndValidateInsertAtOffsetRequest): KastWriteAndValidateResponse {
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

internal suspend fun SkillRpcContext.writeAndValidateReplace(request: KastWriteAndValidateReplaceRangeRequest): KastWriteAndValidateResponse {
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

internal suspend fun SkillRpcContext.applyEditsAndValidate(
    filePath: String,
    edits: List<TextEdit>,
    query: KastWriteAndValidateQuery,
): KastWriteAndValidateResponse {
    requireCapabilities(
        readCapabilities = setOf(ReadCapability.DIAGNOSTICS),
        mutationCapabilities = setOf(
            MutationCapability.APPLY_EDITS,
            MutationCapability.REFRESH_WORKSPACE,
            MutationCapability.OPTIMIZE_IMPORTS,
        ),
    )
    val applyResult = backend.applyEdits(
        ApplyEditsQuery(
            edits = edits,
            fileHashes = currentFileHashes(edits.map(TextEdit::filePath)),
        ).parsed(),
    )
    currentCoroutineContext().ensureActive()
    val admission = awaitSemanticAdmission(listOf(filePath))
    if (!admission.clean) {
        return KastWriteAndValidateSuccessResponse(
            ok = false,
            query = query,
            appliedEdits = applyResult.applied.size,
            importChanges = 0,
            diagnostics = admission,
            logFile = placeholderLogFile(),
        )
    }
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


internal fun SkillRpcContext.currentFileHashes(filePaths: List<String>): List<FileHash> =
    filePaths.distinct().map { filePath ->
        FileHash(
            filePath = filePath,
            hash = FileHashing.sha256(Files.readString(Path.of(filePath))),
        )
    }

internal suspend fun SkillRpcContext.optimizeImports(filePath: String) = run {
    requireMutationCapability(MutationCapability.OPTIMIZE_IMPORTS)
    backend.optimizeImports(ImportOptimizeQuery(filePaths = listOf(filePath)).parsed())
}

internal suspend fun SkillRpcContext.awaitSemanticAdmission(filePaths: List<String>): KastDiagnosticsSummary {
    requireMutationCapability(MutationCapability.REFRESH_WORKSPACE)
    return KastDiagnosticsSummary.from(
        backend.refresh(RefreshQuery(filePaths = filePaths.distinct()).parsed()),
    )
}

internal suspend fun SkillRpcContext.validateFiles(filePaths: List<String>): KastDiagnosticsSummary {
    requireReadCapability(ReadCapability.DIAGNOSTICS)
    return KastDiagnosticsSummary.from(
        result = backend.diagnostics(DiagnosticsQuery(filePaths = filePaths).parsed()),
        maxReturnedErrors = PositiveInt(config.maxResults),
    )
}

internal fun KastWriteAndValidateResponse.toScopeMutationResponse(
    operation: KastScopeMutationOperation,
    affectedFiles: List<String>,
    createdFiles: List<String> = emptyList(),
    editCount: Int,
    placement: KastResolvedPlacement? = null,
): KastScopeMutationResponse = when (this) {
    is KastWriteAndValidateSuccessResponse -> KastScopeMutationSuccessResponse(
        ok = ok,
        operation = operation,
        applied = true,
        affectedFiles = affectedFiles,
        createdFiles = createdFiles,
        editCount = editCount,
        importChanges = importChanges,
        diagnostics = diagnostics,
        placement = placement,
        logFile = logFile,
    )

    is KastWriteAndValidateFailureResponse -> KastScopeMutationFailureResponse(
        operation = operation,
        stage = stage,
        message = message,
        logFile = logFile,
        error = error,
        errorText = errorText,
    )
}

internal fun SkillRpcContext.resolveContent(content: String?, contentFile: String?): String {
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
