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

internal suspend fun SkillRpcContext.addPlacedContent(
    request: KastPlacedScopeMutationRequest,
): KastScopeMutationResponse =
    addContentAtPlacement(
        operation = request.operation,
        workspaceRoot = request.requestedWorkspaceRoot?.value,
        placement = request.placement,
        contentFile = request.contentFilePath.value,
        statementBody = false,
    )

internal suspend fun SkillRpcContext.addContentAtPlacement(
    operation: KastScopeMutationOperation,
    workspaceRoot: String?,
    placement: KastPlacementSelector,
    contentFile: String,
    statementBody: Boolean,
): KastScopeMutationResponse {
    workspaceRootFor(workspaceRoot)
    val resolvedPlacement = resolvePlacement(placement, statementBody)
    val content = resolveContent(null, contentFile)
    val response = applyEditsAndValidate(
        filePath = resolvedPlacement.filePath,
        edits = listOf(
            TextEdit(
                filePath = resolvedPlacement.filePath,
                startOffset = resolvedPlacement.offset,
                endOffset = resolvedPlacement.offset,
                newText = content,
            ),
        ),
        query = KastWriteAndValidateInsertAtOffsetQuery(
            workspaceRoot = workspaceRootFor(workspaceRoot),
            filePath = resolvedPlacement.filePath,
            offset = resolvedPlacement.offset,
        ),
    )
    return response.toScopeMutationResponse(
        operation = operation,
        affectedFiles = listOf(resolvedPlacement.filePath),
        editCount = 1,
        placement = resolvedPlacement,
    )
}

internal suspend fun SkillRpcContext.resolvePlacement(
    placement: KastPlacementSelector,
    statementBody: Boolean,
): KastResolvedPlacement {
    val filePath = filePathForPlacement(placement.scope)
    val offset = when (val anchor = placement.anchor) {
        is KastAtPlacementAnchor -> offsetForAnchor(placement.scope, anchor.anchor, statementBody)
        is KastAfterSymbolPlacementAnchor -> {
            val resolvedAnchor = resolveSymbolForPlacement(anchor.symbol, anchor.fileHint, anchor.kind, anchor.containingType)
            requireAnchorInPlacementFile(filePath, resolvedAnchor)
            resolvedAnchor.declarationEndOffset()
        }
        is KastBeforeSymbolPlacementAnchor -> {
            val resolvedAnchor = resolveSymbolForPlacement(anchor.symbol, anchor.fileHint, anchor.kind, anchor.containingType)
            requireAnchorInPlacementFile(filePath, resolvedAnchor)
            resolvedAnchor.declarationStartOffset()
        }
    }
    return KastResolvedPlacement(
        filePath = filePath,
        offset = offset,
        scope = placement.scope,
        anchor = placement.anchor,
    )
}

internal suspend fun SkillRpcContext.filePathForPlacement(scope: KastPlacementScopeSelector): String = when (scope) {
    is KastFilePlacementScope -> scope.insideFile.normalizedAbsolutePath()
    is KastNamedPlacementScope -> resolveSymbolForPlacement(
        scope.insideScope,
        scope.fileHint,
        scope.kind,
        scope.containingType,
    ).filePath
}

internal suspend fun SkillRpcContext.offsetForAnchor(
    scope: KastPlacementScopeSelector,
    anchor: KastPlacementAnchor,
    statementBody: Boolean,
): Int = when (scope) {
    is KastFilePlacementScope -> fileOffsetForAnchor(scope.insideFile.normalizedAbsolutePath(), anchor)
    is KastNamedPlacementScope -> {
        val resolved = resolveSymbolForPlacement(
            scope.insideScope,
            scope.fileHint,
            scope.kind,
            scope.containingType,
        )
        if (statementBody) {
            executableBodyOffset(resolved, anchor)
        } else {
            symbolOffsetForAnchor(resolved, anchor)
        }
    }
}

internal suspend fun SkillRpcContext.fileOffsetForAnchor(filePath: String, anchor: KastPlacementAnchor): Int = when (anchor) {
    KastPlacementAnchor.FILE_TOP -> 0
    KastPlacementAnchor.FILE_BOTTOM -> Files.readString(Path.of(filePath)).length
    KastPlacementAnchor.AFTER_IMPORTS -> semanticInsertionOffset(filePath, 0, SemanticInsertionTarget.AFTER_IMPORTS)
    KastPlacementAnchor.BODY_START,
    KastPlacementAnchor.BODY_END -> throw ValidationException("$anchor requires --inside-scope")
}

internal suspend fun SkillRpcContext.symbolOffsetForAnchor(
    resolved: ResolvedNamedSymbol,
    anchor: KastPlacementAnchor,
): Int = when (anchor) {
    KastPlacementAnchor.BODY_START -> semanticInsertionOffset(resolved.filePath, resolved.offset, SemanticInsertionTarget.CLASS_BODY_START)
    KastPlacementAnchor.BODY_END -> semanticInsertionOffset(resolved.filePath, resolved.offset, SemanticInsertionTarget.CLASS_BODY_END)
    KastPlacementAnchor.FILE_TOP -> 0
    KastPlacementAnchor.FILE_BOTTOM -> Files.readString(Path.of(resolved.filePath)).length
    KastPlacementAnchor.AFTER_IMPORTS -> semanticInsertionOffset(resolved.filePath, 0, SemanticInsertionTarget.AFTER_IMPORTS)
}

internal fun SkillRpcContext.executableBodyOffset(
    resolved: ResolvedNamedSymbol,
    anchor: KastPlacementAnchor,
): Int {
    if (anchor != KastPlacementAnchor.BODY_END) {
        throw ValidationException("add-statement currently supports only body-end")
    }
    val declarationScope = resolved.symbol.declarationScope
        ?: throw ValidationException("Resolved executable scope did not include declaration scope")
    val sourceText = declarationScope.sourceText
        ?: throw ValidationException("Resolved executable scope did not include source text")
    val relativeOffset = sourceText.lastIndexOf('}')
    if (relativeOffset < 0) {
        throw ValidationException("Resolved executable scope does not have a block body")
    }
    return declarationScope.startOffset + relativeOffset
}

internal suspend fun SkillRpcContext.semanticInsertionOffset(
    filePath: String,
    offset: Int,
    target: SemanticInsertionTarget,
): Int {
    requireReadCapability(ReadCapability.SEMANTIC_INSERTION_POINT)
    return backend.semanticInsertionPoint(
        io.github.amichne.kast.api.contract.SemanticInsertionQuery(
            position = FilePosition(filePath = filePath, offset = offset),
            target = target,
        ).parsed(),
    ).insertionOffset
}

internal suspend fun SkillRpcContext.resolveSymbolForPlacement(
    symbol: String,
    fileHint: String?,
    kind: WrapperNamedSymbolKind?,
    containingType: String?,
): ResolvedNamedSymbol =
    resolveNamedSymbol(
        symbolName = symbol,
        fileHint = fileHint,
        kind = kind,
        containingType = containingType,
        includeDeclarationScope = true,
    ) ?: throw ValidationException("No symbol matching '$symbol' found in workspace")

internal fun SkillRpcContext.requireAnchorInPlacementFile(
    placementFilePath: String,
    resolvedAnchor: ResolvedNamedSymbol,
) {
    if (resolvedAnchor.filePath != placementFilePath) {
        throw ValidationException(
            "Anchor symbol '${resolvedAnchor.symbol.fqName}' resolved in ${resolvedAnchor.filePath}, outside placement file $placementFilePath",
        )
    }
}

internal fun ResolvedNamedSymbol.declarationStartOffset(): Int =
    symbol.declarationScope?.startOffset ?: symbol.location.startOffset

internal fun ResolvedNamedSymbol.declarationEndOffset(): Int =
    symbol.declarationScope?.endOffset ?: symbol.location.endOffset

internal fun KastStatementPlacementAnchor.toPlacementAnchor(): KastPlacementAnchor = when (this) {
    KastStatementPlacementAnchor.BODY_END -> KastPlacementAnchor.BODY_END
}
