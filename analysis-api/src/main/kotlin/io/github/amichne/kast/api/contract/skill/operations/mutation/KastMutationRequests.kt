package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.protocol.ApiErrorResponse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
sealed interface KastWriteAndValidateRequest

@Serializable
@SerialName("CREATE_FILE_REQUEST")
data class KastWriteAndValidateCreateFileRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val content: String? = null,
    val contentFile: String? = null,
) : KastWriteAndValidateRequest

@Serializable
@SerialName("INSERT_AT_OFFSET_REQUEST")
data class KastWriteAndValidateInsertAtOffsetRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val offset: Int,
    val content: String? = null,
    val contentFile: String? = null,
) : KastWriteAndValidateRequest

@Serializable
@SerialName("REPLACE_RANGE_REQUEST")
data class KastWriteAndValidateReplaceRangeRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val content: String? = null,
    val contentFile: String? = null,
) : KastWriteAndValidateRequest

@Serializable
enum class KastScopeMutationOperation {
    ADD_FILE,
    ADD_DECLARATION,
    ADD_IMPLEMENTATION,
    ADD_STATEMENT,
    REPLACE_DECLARATION,
}

@Serializable
@SerialName("ADD_FILE_REQUEST")
data class KastAddFileRequest(
    val workspaceRoot: String? = null,
    val filePath: String,
    val contentFile: String,
) : KastFileScopeMutationRequest {
    override val requestedWorkspaceRoot: NormalizedPath?
        get() = workspaceRoot.toOptionalNormalizedRequestPath()
    override val targetFilePath: NormalizedPath
        get() = filePath.toNormalizedRequestPath()
    override val contentFilePath: NormalizedPath
        get() = contentFile.toNormalizedRequestPath()
    override val operation: KastScopeMutationOperation
        get() = KastScopeMutationOperation.ADD_FILE
}

interface KastWorkspaceScopedRequest {
    val requestedWorkspaceRoot: NormalizedPath?
}

interface KastContentFileRequest {
    val contentFilePath: NormalizedPath
}

interface KastScopeMutationRequest : KastWorkspaceScopedRequest, KastContentFileRequest {
    val operation: KastScopeMutationOperation
}

interface KastFileScopeMutationRequest : KastScopeMutationRequest {
    val targetFilePath: NormalizedPath
}

interface KastPlacedScopeMutationRequest : KastScopeMutationRequest {
    val placement: KastPlacementSelector
}

interface KastNamedScopeMutationRequest : KastScopeMutationRequest {
    val requestedInsideScope: NonBlankString
}

interface KastSymbolScopeMutationRequest : KastScopeMutationRequest {
    val requestedSymbol: NonBlankString
}

@Serializable
sealed interface KastPlacementScopeSelector

@Serializable
@SerialName("NAMED_SCOPE")
data class KastNamedPlacementScope(
    val insideScope: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
) : KastPlacementScopeSelector

@Serializable
@SerialName("FILE_SCOPE")
data class KastFilePlacementScope(
    val insideFile: String,
) : KastPlacementScopeSelector

@Serializable
enum class KastPlacementAnchor {
    @SerialName("body-start")
    BODY_START,

    @SerialName("body-end")
    BODY_END,

    @SerialName("file-top")
    FILE_TOP,

    @SerialName("file-bottom")
    FILE_BOTTOM,

    @SerialName("after-imports")
    AFTER_IMPORTS,
}

@Serializable
enum class KastStatementPlacementAnchor {
    @SerialName("body-end")
    BODY_END,
}

@Serializable
sealed interface KastPlacementAnchorSelector

@Serializable
@SerialName("AT_ANCHOR")
data class KastAtPlacementAnchor(
    val anchor: KastPlacementAnchor,
) : KastPlacementAnchorSelector

@Serializable
@SerialName("AFTER_SYMBOL")
data class KastAfterSymbolPlacementAnchor(
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
) : KastPlacementAnchorSelector

@Serializable
@SerialName("BEFORE_SYMBOL")
data class KastBeforeSymbolPlacementAnchor(
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
) : KastPlacementAnchorSelector

@Serializable
data class KastPlacementSelector(
    val scope: KastPlacementScopeSelector,
    val anchor: KastPlacementAnchorSelector,
)

@Serializable
data class KastResolvedPlacement(
    val filePath: String,
    val offset: Int,
    val scope: KastPlacementScopeSelector,
    val anchor: KastPlacementAnchorSelector,
)

@Serializable
@SerialName("ADD_DECLARATION_REQUEST")
data class KastAddDeclarationRequest(
    val workspaceRoot: String? = null,
    override val placement: KastPlacementSelector,
    val contentFile: String,
) : KastPlacedScopeMutationRequest {
    override val requestedWorkspaceRoot: NormalizedPath?
        get() = workspaceRoot.toOptionalNormalizedRequestPath()
    override val contentFilePath: NormalizedPath
        get() = contentFile.toNormalizedRequestPath()
    override val operation: KastScopeMutationOperation
        get() = KastScopeMutationOperation.ADD_DECLARATION
}

@Serializable
@SerialName("ADD_IMPLEMENTATION_REQUEST")
data class KastAddImplementationRequest(
    val workspaceRoot: String? = null,
    override val placement: KastPlacementSelector,
    val contentFile: String,
) : KastPlacedScopeMutationRequest {
    override val requestedWorkspaceRoot: NormalizedPath?
        get() = workspaceRoot.toOptionalNormalizedRequestPath()
    override val contentFilePath: NormalizedPath
        get() = contentFile.toNormalizedRequestPath()
    override val operation: KastScopeMutationOperation
        get() = KastScopeMutationOperation.ADD_IMPLEMENTATION
}

@Serializable
@SerialName("ADD_STATEMENT_REQUEST")
data class KastAddStatementRequest(
    val workspaceRoot: String? = null,
    val insideScope: String,
    val anchor: KastStatementPlacementAnchor,
    val contentFile: String,
) : KastNamedScopeMutationRequest {
    override val requestedWorkspaceRoot: NormalizedPath?
        get() = workspaceRoot.toOptionalNormalizedRequestPath()
    override val requestedInsideScope: NonBlankString
        get() = NonBlankString(insideScope)
    override val contentFilePath: NormalizedPath
        get() = contentFile.toNormalizedRequestPath()
    override val operation: KastScopeMutationOperation
        get() = KastScopeMutationOperation.ADD_STATEMENT
}

@Serializable
sealed interface KastReplaceDeclarationRequest : KastScopeMutationRequest {
    override val operation: KastScopeMutationOperation
        get() = KastScopeMutationOperation.REPLACE_DECLARATION
}

@Serializable
@SerialName("REPLACE_DECLARATION_BY_SYMBOL_REQUEST")
data class KastReplaceDeclarationBySymbolRequest(
    val workspaceRoot: String? = null,
    val symbol: String,
    val contentFile: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
) : KastReplaceDeclarationRequest, KastSymbolScopeMutationRequest {
    override val requestedWorkspaceRoot: NormalizedPath?
        get() = workspaceRoot.toOptionalNormalizedRequestPath()
    override val requestedSymbol: NonBlankString
        get() = NonBlankString(symbol)
    override val contentFilePath: NormalizedPath
        get() = contentFile.toNormalizedRequestPath()
}

@Serializable
@SerialName("REPLACE_DECLARATION_BY_SELECTOR_HANDLE_REQUEST")
data class KastReplaceDeclarationBySelectorHandleRequest(
    val workspaceRoot: String? = null,
    val selectorHandle: String,
    val contentFile: String,
) : KastReplaceDeclarationRequest {
    override val requestedWorkspaceRoot: NormalizedPath?
        get() = workspaceRoot.toOptionalNormalizedRequestPath()
    override val contentFilePath: NormalizedPath
        get() = contentFile.toNormalizedRequestPath()
}

private fun String?.toOptionalNormalizedRequestPath(): NormalizedPath? =
    this?.takeIf(String::isNotBlank)?.toNormalizedRequestPath()

private fun String.toNormalizedRequestPath(): NormalizedPath =
    NormalizedPath.ofAbsolute(Path.of(NonBlankString(this).value))
