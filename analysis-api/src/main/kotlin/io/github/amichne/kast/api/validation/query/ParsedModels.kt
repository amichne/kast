package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.SemanticGraphExternalBoundaryFailureId
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.protocol.*
import java.nio.file.FileSystems

/**
 * Parsed query contract for requests anchored at a validated source position.
 */
interface PositionQuery { val position: ParsedFilePosition }

/**
 * Parsed query contract for requests whose result count is bounded by a positive limit.
 */
interface BoundedQuery { val maxResults: PositiveInt }

/**
 * Parsed query contract for traversal requests whose depth is zero or greater.
 */
interface DepthBoundedQuery { val depth: NonNegativeInt }

/**
 * Validated internal representation of a [FilePosition].
 * Constructed at system boundaries to consolidate validation and parsing.
 */
data class ParsedFilePosition(
    val filePath: NormalizedPath,
    val offset: ByteOffset,
)

/**
 * Validated internal representation of a [Location].
 * Constructed at system boundaries to consolidate validation and parsing.
 */
data class ParsedLocation(
    val filePath: NormalizedPath,
    val startOffset: ByteOffset,
    val endOffset: ByteOffset,
    val startLine: LineNumber,
    val startColumn: ColumnNumber,
    val preview: String,
)

/**
 * Validated internal representation of a [TextEdit].
 * Constructed at system boundaries to consolidate validation and parsing.
 */
data class ParsedTextEdit(
    val filePath: NormalizedPath,
    val startOffset: ByteOffset,
    val endOffset: ByteOffset,
    val newText: String,
)

data class ParsedFileHash(
    val filePath: NormalizedPath,
    val hash: String,
)

sealed interface ParsedFileOperation {
    val filePath: NormalizedPath

    data class CreateFile(
        override val filePath: NormalizedPath,
        val content: String,
        val parentPolicy: CreateFileParentPolicy,
    ) : ParsedFileOperation

    data class DeleteFile(
        override val filePath: NormalizedPath,
        val expectedHash: String,
    ) : ParsedFileOperation
}

data class ParsedSymbolQuery(
    override val position: ParsedFilePosition,
    val includeDeclarationScope: Boolean,
    val includeDocumentation: Boolean,
) : PositionQuery

data class ParsedCallHierarchyQuery(
    override val position: ParsedFilePosition,
    val direction: CallDirection,
    override val depth: NonNegativeInt,
    val maxTotalCalls: PositiveInt,
    val maxChildrenPerNode: PositiveInt,
    val timeoutMillis: PositiveLong?,
) : PositionQuery, DepthBoundedQuery

data class ParsedTypeHierarchyQuery(
    override val position: ParsedFilePosition,
    val direction: TypeHierarchyDirection,
    override val depth: NonNegativeInt,
    override val maxResults: PositiveInt,
) : PositionQuery, DepthBoundedQuery, BoundedQuery

data class ParsedSemanticInsertionQuery(
    override val position: ParsedFilePosition,
    val target: SemanticInsertionTarget,
) : PositionQuery

data class ParsedRenameQuery(
    override val position: ParsedFilePosition,
    val newName: NonBlankString,
    val dryRun: Boolean,
) : PositionQuery

data class ParsedReplacementPlanQuery(
    val target: SymbolIdentity,
    val proposedDeclaration: NonBlankString,
)

data class ParsedAddFilePlanQuery(
    val targetPath: AdditionTargetPath,
    val proposedContent: NonBlankString,
)

data class ParsedAddDeclarationPlanQuery(
    val targetPath: AdditionTargetPath,
    val expectedCurrentSha256: AdditionTargetPreimageSha256,
    val proposedDeclaration: NonBlankString,
)

data class ParsedExactFileImageQuery(
    val filePath: NormalizedPath,
    val expectedCurrentSha256: ExactFileImageSha256,
    val content: ExactByteImage,
    val expectedResultSha256: ExactFileImageSha256,
    val mutationAttemptId: MutationAttemptId? = null,
    val mutationScratch: ParsedMutationScratchSet? = null,
)

data class ParsedImportOptimizeQuery(
    val filePaths: NonEmptyList<NormalizedPath>,
)

data class ParsedApplyEditsQuery(
    val edits: List<ParsedTextEdit>,
    val fileHashes: List<ParsedFileHash>,
    val fileOperations: List<ParsedFileOperation>,
    val mutationAttemptId: MutationAttemptId? = null,
    val mutationScratchSets: List<ParsedMutationScratchSet> = emptyList(),
)

data class ParsedRefreshQuery(
    val filePaths: List<NormalizedPath>,
    val externalFailureIds: List<SemanticGraphExternalBoundaryFailureId>,
)

data class ParsedFileOutlineQuery(
    val filePath: NormalizedPath,
)

data class ParsedWorkspaceSymbolQuery(
    val pattern: NonBlankString,
    val kind: SymbolKind?,
    override val maxResults: PositiveInt,
    val regex: Boolean,
    val includeDeclarationScope: Boolean,
) : BoundedQuery

data class ParsedWorkspaceSearchQuery(
    val pattern: NonBlankString,
    override val maxResults: PositiveInt,
    val regex: Boolean,
    val fileGlob: NonBlankString?,
    val caseSensitive: Boolean,
) : BoundedQuery

data class ParsedImplementationsQuery(
    override val position: ParsedFilePosition,
    override val maxResults: PositiveInt,
) : PositionQuery, BoundedQuery

data class ParsedCodeActionsQuery(
    override val position: ParsedFilePosition,
    val diagnosticCode: String?,
) : PositionQuery

data class ParsedCompletionsQuery(
    override val position: ParsedFilePosition,
    override val maxResults: PositiveInt,
    val kindFilter: Set<SymbolKind>?,
) : PositionQuery, BoundedQuery

/**
 * Parse a wire-format [FilePosition] into a validated [ParsedFilePosition].
 * Throws [ValidationException] if the path is not absolute or the offset is negative.
 */
fun FilePosition.parsed(): ParsedFilePosition = ParsedFilePosition(
    filePath = NormalizedPath.parse(filePath),
    offset = ByteOffset(offset),
)

/**
 * Parse a wire-format [Location] into a validated [ParsedLocation].
 */
fun Location.parsed(): ParsedLocation = ParsedLocation(
    filePath = NormalizedPath.parse(filePath),
    startOffset = ByteOffset(startOffset),
    endOffset = ByteOffset(endOffset),
    startLine = LineNumber(startLine),
    startColumn = ColumnNumber(startColumn),
    preview = preview,
)

/**
 * Parse a wire-format [TextEdit] into a validated [ParsedTextEdit].
 */
fun TextEdit.parsed(): ParsedTextEdit = ParsedTextEdit(
    filePath = NormalizedPath.parse(filePath),
    startOffset = ByteOffset(startOffset),
    endOffset = ByteOffset(endOffset),
    newText = newText,
)

fun FileHash.parsed(): ParsedFileHash = ParsedFileHash(
    filePath = NormalizedPath.parse(filePath),
    hash = hash,
)

fun FileOperation.parsed(): ParsedFileOperation = when (this) {
    is FileOperation.CreateFile -> ParsedFileOperation.CreateFile(
        filePath = NormalizedPath.parse(filePath),
        content = content,
        parentPolicy = parentPolicy,
    )

    is FileOperation.DeleteFile -> ParsedFileOperation.DeleteFile(
        filePath = NormalizedPath.parse(filePath),
        expectedHash = expectedHash,
    )
}

fun SymbolQuery.parsed(): ParsedSymbolQuery = validationBoundary {
    ParsedSymbolQuery(
        position = position.parsed(),
        includeDeclarationScope = includeDeclarationScope,
        includeDocumentation = includeDocumentation,
    )
}

fun ReferencesQuery.parsed(): ParsedReferencesQuery = validationBoundary {
    ParsedReferencesQuery(
        position = position.parsed(),
        includeDeclaration = includeDeclaration,
        includeUsageSiteScope = includeUsageSiteScope,
        maxResults = PositiveInt(maxResults),
        pageToken = pageToken?.let(ReferencePageToken::parse),
        selector = selector,
    )
}

fun CallHierarchyQuery.parsed(): ParsedCallHierarchyQuery = validationBoundary {
    ParsedCallHierarchyQuery(
        position = position.parsed(),
        direction = direction,
        depth = NonNegativeInt(depth),
        maxTotalCalls = PositiveInt(maxTotalCalls),
        maxChildrenPerNode = PositiveInt(maxChildrenPerNode),
        timeoutMillis = timeoutMillis?.let(::PositiveLong),
    )
}

fun TypeHierarchyQuery.parsed(): ParsedTypeHierarchyQuery = validationBoundary {
    ParsedTypeHierarchyQuery(
        position = position.parsed(),
        direction = direction,
        depth = NonNegativeInt(depth),
        maxResults = PositiveInt(maxResults),
    )
}

fun SemanticInsertionQuery.parsed(): ParsedSemanticInsertionQuery = validationBoundary {
    ParsedSemanticInsertionQuery(
        position = position.parsed(),
        target = target,
    )
}

fun DiagnosticsQuery.parsed(): ParsedDiagnosticsQuery = validationBoundary {
    ParsedDiagnosticsQuery(
        filePaths = NonEmptyList(filePaths.map(NormalizedPath::parse)),
        maxResults = PositiveInt(maxResults),
        pageToken = pageToken?.let(DiagnosticPageToken::parse),
    )
}

fun RenameQuery.parsed(): ParsedRenameQuery = validationBoundary {
    ParsedRenameQuery(
        position = position.parsed(),
        newName = NonBlankString(newName),
        dryRun = dryRun,
    )
}

fun ReplacementPlanQuery.parsed(): ParsedReplacementPlanQuery = validationBoundary {
    ParsedReplacementPlanQuery(
        target = target,
        proposedDeclaration = NonBlankString(proposedDeclaration),
    )
}

fun AddFilePlanQuery.parsed(): ParsedAddFilePlanQuery = validationBoundary {
    requireStrictNormalizedKotlinText(proposedContent, allowFinalLf = true)
    ParsedAddFilePlanQuery(targetPath, NonBlankString(proposedContent))
}

fun AddDeclarationPlanQuery.parsed(): ParsedAddDeclarationPlanQuery = validationBoundary {
    requireStrictNormalizedKotlinText(proposedDeclaration, allowFinalLf = false)
    ParsedAddDeclarationPlanQuery(
        targetPath = targetPath,
        expectedCurrentSha256 = expectedCurrentSha256,
        proposedDeclaration = NonBlankString(proposedDeclaration),
    )
}

private fun requireStrictNormalizedKotlinText(value: String, allowFinalLf: Boolean) {
    require('\r' !in value && '\uFEFF' !in value) {
        "Inline Kotlin source must use normalized LF text without a byte-order mark"
    }
    if (!allowFinalLf) require(!value.endsWith('\n')) {
        "Inline Kotlin declaration must not contain a final line break"
    }
    require(runCatching {
        java.nio.charset.StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
    }.isSuccess) { "Inline Kotlin source must be strict UTF-8 encodable" }
}

private inline fun <T> validationBoundary(block: () -> T): T {
    try {
        return block()
    } catch (exception: ValidationException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        throw ValidationException(exception.message ?: "Invalid request")
    }
}
