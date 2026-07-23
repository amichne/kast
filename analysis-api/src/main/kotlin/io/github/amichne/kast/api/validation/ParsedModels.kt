package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
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

data class ParsedImportOptimizeQuery(
    val filePaths: NonEmptyList<NormalizedPath>,
)

data class ParsedApplyEditsQuery(
    val edits: List<ParsedTextEdit>,
    val fileHashes: List<ParsedFileHash>,
    val fileOperations: List<ParsedFileOperation>,
)

data class ParsedRefreshQuery(
    val filePaths: List<NormalizedPath>,
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

fun ImportOptimizeQuery.parsed(): ParsedImportOptimizeQuery = validationBoundary {
    ParsedImportOptimizeQuery(
        filePaths = NonEmptyList(filePaths.map(NormalizedPath::parse)),
    )
}

fun ApplyEditsQuery.parsed(): ParsedApplyEditsQuery = validationBoundary {
    ParsedApplyEditsQuery(
        edits = edits.map(TextEdit::parsed),
        fileHashes = fileHashes.map(FileHash::parsed),
        fileOperations = fileOperations.map(FileOperation::parsed),
    )
}

fun RefreshQuery.parsed(): ParsedRefreshQuery = validationBoundary {
    ParsedRefreshQuery(filePaths = filePaths.map(NormalizedPath::parse))
}

fun FileOutlineQuery.parsed(): ParsedFileOutlineQuery = validationBoundary {
    ParsedFileOutlineQuery(filePath = NormalizedPath.parse(filePath))
}

fun WorkspaceSymbolQuery.parsed(): ParsedWorkspaceSymbolQuery = validationBoundary {
    ParsedWorkspaceSymbolQuery(
        pattern = NonBlankString(pattern),
        kind = kind,
        maxResults = PositiveInt(maxResults),
        regex = regex,
        includeDeclarationScope = includeDeclarationScope,
    )
}

fun WorkspaceSearchQuery.parsed(): ParsedWorkspaceSearchQuery = validationBoundary {
    val parsedPattern = NonBlankString(pattern)
    val parsedFileGlob = fileGlob?.let(::NonBlankString)
    if (regex) {
        Regex(
            parsedPattern.value,
            if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
        )
    }
    parsedFileGlob?.value?.let { glob ->
        FileSystems.getDefault().getPathMatcher("glob:$glob")
    }
    ParsedWorkspaceSearchQuery(
        pattern = parsedPattern,
        maxResults = PositiveInt(maxResults),
        regex = regex,
        fileGlob = parsedFileGlob,
        caseSensitive = caseSensitive,
    )
}

fun WorkspaceFilesQuery.parsed(): ParsedWorkspaceFilesQuery = validationBoundary {
    ParsedWorkspaceFilesQuery(
        kindDomain = kindDomain,
        moduleName = moduleName?.let(::NonBlankString),
        includeFiles = includeFiles,
        maxFilesPerModule = maxFilesPerModule?.let(::PositiveInt),
        snapshotToken = snapshotToken?.let(WorkspaceFileSnapshotToken::parse),
        pageToken = pageToken?.let(WorkspaceFilePageToken::parse),
    )
}

fun SemanticGraphQuery.parsed(): ParsedSemanticGraphQuery = validationBoundary {
    ParsedSemanticGraphQuery(
        filePaths = filePaths.distinct().sorted(),
        removedFilePaths = removedFilePaths.distinct().sorted(),
    )
}

fun ImplementationsQuery.parsed(): ParsedImplementationsQuery = validationBoundary {
    ParsedImplementationsQuery(
        position = position.parsed(),
        maxResults = PositiveInt(maxResults),
    )
}

fun CodeActionsQuery.parsed(): ParsedCodeActionsQuery = validationBoundary {
    ParsedCodeActionsQuery(
        position = position.parsed(),
        diagnosticCode = diagnosticCode,
    )
}

fun CompletionsQuery.parsed(): ParsedCompletionsQuery = validationBoundary {
    ParsedCompletionsQuery(
        position = position.parsed(),
        maxResults = PositiveInt(maxResults),
        kindFilter = kindFilter,
    )
}

fun ParsedTextEdit.toWire(): TextEdit = TextEdit(
    filePath = filePath.value,
    startOffset = startOffset.value,
    endOffset = endOffset.value,
    newText = newText,
)

fun ParsedFileHash.toWire(): FileHash = FileHash(
    filePath = filePath.value,
    hash = hash,
)

fun ParsedFileOperation.toWire(): FileOperation = when (this) {
    is ParsedFileOperation.CreateFile -> FileOperation.CreateFile(
        filePath = filePath.value,
        content = content,
    )

    is ParsedFileOperation.DeleteFile -> FileOperation.DeleteFile(
        filePath = filePath.value,
        expectedHash = expectedHash,
    )
}

fun ParsedApplyEditsQuery.toWire(): ApplyEditsQuery = ApplyEditsQuery(
    edits = edits.map(ParsedTextEdit::toWire),
    fileHashes = fileHashes.map(ParsedFileHash::toWire),
    fileOperations = fileOperations.map(ParsedFileOperation::toWire),
)

private inline fun <T> validationBoundary(block: () -> T): T {
    try {
        return block()
    } catch (exception: ValidationException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        throw ValidationException(exception.message ?: "Invalid request")
    }
}
