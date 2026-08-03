package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.SemanticGraphExternalBoundaryFailureId
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.protocol.*
import java.nio.file.FileSystems

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
