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

fun ExactFileImageQuery.parsed(): ParsedExactFileImageQuery = validationBoundary {
    val content = ExactByteImage.of(
        bytes = contentBase64.copyBytes(),
        expectedSha256 = expectedResultSha256.value,
    )
    val attemptId = mutationAttemptId?.let(MutationAttemptId::parse)
    require((attemptId == null) == (mutationScratch == null)) {
        "Exact file-image CAS requires scratch if and only if mutationAttemptId is present"
    }
    val target = NormalizedPath.parse(filePath.value)
    ParsedExactFileImageQuery(
        filePath = target,
        expectedCurrentSha256 = expectedCurrentSha256,
        content = content,
        expectedResultSha256 = expectedResultSha256,
        mutationAttemptId = attemptId,
        mutationScratch = mutationScratch?.parsed(
            expectedOwnerAttemptId = requireNotNull(attemptId),
            expectedTarget = target,
        ),
    )
}

fun ImportOptimizeQuery.parsed(): ParsedImportOptimizeQuery = validationBoundary {
    ParsedImportOptimizeQuery(
        filePaths = NonEmptyList(filePaths.map(NormalizedPath::parse)),
    )
}

fun ApplyEditsQuery.parsed(): ParsedApplyEditsQuery = validationBoundary {
    val parsedOperations = fileOperations.map(FileOperation::parsed)
    val attemptId = mutationAttemptId?.let(MutationAttemptId::parse)
    require(attemptId == null || edits.isEmpty()) {
        "Verified apply-edits accepts file operations only; verified text replacement uses exact CAS"
    }
    require(if (attemptId == null) mutationScratchSets.isEmpty() else mutationScratchSets.size == parsedOperations.size) {
        "Verified apply-edits requires exactly one scratch set per file operation; legacy apply accepts none"
    }
    require(attemptId == null || parsedOperations.all { operation ->
        operation !is ParsedFileOperation.CreateFile ||
            operation.parentPolicy == CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS
    }) { "Verified file creation requires existing parent directories" }
    val parsedScratchSets = if (attemptId == null) {
        emptyList()
    } else {
        mutationScratchSets.mapIndexed { index, scratch ->
            scratch.parsed(
                expectedOwnerAttemptId = attemptId,
                expectedTarget = parsedOperations[index].filePath,
            )
        }.also { scratchSets ->
            require(scratchSets.map(ParsedMutationScratchSet::transitionIndex).distinct().size == scratchSets.size) {
                "Verified apply-edits mutation scratch transition indices must be globally unique"
            }
            require(scratchSets.flatMap(ParsedMutationScratchSet::ownedPaths).distinct().size == scratchSets.size * 4) {
                "Verified apply-edits mutation scratch role paths must be unique across the batch"
            }
        }
    }
    ParsedApplyEditsQuery(
        edits = edits.map(TextEdit::parsed),
        fileHashes = fileHashes.map(FileHash::parsed),
        fileOperations = parsedOperations,
        mutationAttemptId = attemptId,
        mutationScratchSets = parsedScratchSets,
    )
}

fun RefreshQuery.parsed(): ParsedRefreshQuery = validationBoundary {
    require(filePaths.isEmpty() || externalFailureIds.isEmpty()) {
        "Refresh file paths and external failure IDs are mutually exclusive"
    }
    val parsedFailureIds = externalFailureIds.map(SemanticGraphExternalBoundaryFailureId::parse)
    require(parsedFailureIds.distinct().size == parsedFailureIds.size) {
        "External failure IDs must be unique"
    }
    ParsedRefreshQuery(
        filePaths = filePaths.map(NormalizedPath::parse),
        externalFailureIds = parsedFailureIds,
    )
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
        expectedGeneration = expectedGeneration,
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

private inline fun <T> validationBoundary(block: () -> T): T {
    try {
        return block()
    } catch (exception: ValidationException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        throw ValidationException(exception.message ?: "Invalid request")
    }
}
