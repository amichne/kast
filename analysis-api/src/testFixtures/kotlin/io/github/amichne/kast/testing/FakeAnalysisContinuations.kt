package io.github.amichne.kast.testing

import io.github.amichne.kast.api.continuation.*
import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.validation.*
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun FakeAnalysisBackend.findReferencesResult(query: ParsedReferencesQuery): ReferencesResult {
    requireAnchor(query.position)

    val declaration = if (query.includeDeclaration) symbol else null
    val allReferences = referenceLocations
        .distinctBy { location -> Triple(location.filePath, location.startOffset, location.endOffset) }
        .sortedWith(compareBy({ it.filePath }, { it.startOffset }, { it.endOffset }))
    val identity = fakeReferenceIdentity(query)
    val pageToken = query.pageToken
    if (pageToken == null) {
        val page = fakeReferencePage(
            allReferences = allReferences,
            pageStart = 0,
            maxResults = query.maxResults.value,
        )
        val nextPageToken = if (page.hasMore) {
            issueReferenceContinuation(identity, FakeReferenceContinuation(page.nextOffset)).value
        } else {
            null
        }
        return page.toResult(declaration, allReferences.size, nextPageToken)
    }

    return when (val consumed = referenceContinuations.consume(pageToken, identity) { continuation ->
        val page = fakeReferencePage(
            allReferences = allReferences,
            pageStart = continuation.offset,
            maxResults = query.maxResults.value,
        )
        if (page.hasMore) {
            continuation.offset = page.nextOffset
            ContinuationTransition.Reissue(page, identity)
        } else {
            ContinuationTransition.Complete(page)
        }
    }) {
        is ContinuationConsumeResult.Completed ->
            consumed.output.toResult(declaration, allReferences.size, nextPageToken = null)
        is ContinuationConsumeResult.Reissued ->
            consumed.output.toResult(declaration, allReferences.size, consumed.token.value)
        is ContinuationConsumeResult.Rejected -> throwReferenceContinuationFailure(consumed.failure)
    }
}

internal suspend fun FakeAnalysisBackend.diagnosticsResult(query: ParsedDiagnosticsQuery): DiagnosticsResult {
    val filePaths = query.filePaths.value
    filePaths.forEach { requireKnownFile(it.value) }
    val identity = FakeDiagnosticIdentity(
        filePaths = filePaths.map { path -> path.value },
        maxResults = query.maxResults.value,
    )
    val pageToken = query.pageToken
    if (pageToken != null) {
        return when (val consumed = diagnosticContinuations.consume(pageToken, identity) { continuation ->
            val page = continuation.page(query.maxResults.value)
            if (page.hasMore) {
                continuation.offset = page.nextOffset
                ContinuationTransition.Reissue(page, identity)
            } else {
                ContinuationTransition.Complete(page)
            }
        }) {
            is ContinuationConsumeResult.Completed -> consumed.output.toResult(nextPageToken = null)
            is ContinuationConsumeResult.Reissued -> consumed.output.toResult(consumed.token.value)
            is ContinuationConsumeResult.Rejected -> throwDiagnosticContinuationFailure(consumed.failure)
        }
    }

    val diagnostics = filePaths
        .flatMap { filePath -> diagnosticsByFile[filePath.value].orEmpty() }
        .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }))
    val fileStatuses = filePaths.map(FileAnalysisStatus::analyzed)
    val fileHashes = filePaths.map { filePath ->
        FileHash(
            filePath = filePath.value,
            hash = FileHashing.sha256(Files.readString(Path.of(filePath.value))),
        )
    }
    val page = FakeDiagnosticPage(
        diagnostics = diagnostics,
        fileStatuses = fileStatuses,
        fileHashes = fileHashes,
        pageOffset = 0,
        maxResults = query.maxResults.value,
    )
    val nextPageToken = if (page.hasMore) {
        issueDiagnosticContinuation(
            identity,
            FakeDiagnosticContinuation(
                diagnostics = diagnostics,
                fileStatuses = fileStatuses,
                fileHashes = fileHashes,
                offset = page.nextOffset,
            ),
        ).value
    } else {
        null
    }
    return page.toResult(nextPageToken)
}

private fun FakeAnalysisBackend.fakeReferenceIdentity(query: ParsedReferencesQuery): FakeReferenceIdentity = FakeReferenceIdentity(
    filePath = query.position.filePath.value,
    offset = query.position.offset.value,
    includeDeclaration = query.includeDeclaration,
    includeUsageSiteScope = query.includeUsageSiteScope,
    maxResults = query.maxResults.value,
)

private fun fakeReferencePage(
    allReferences: List<Location>,
    pageStart: Int,
    maxResults: Int,
): FakeReferencePage {
    val probeLimit = if (maxResults == Int.MAX_VALUE) maxResults else maxResults + 1
    val pageProbe = allReferences.drop(pageStart).take(probeLimit)
    val references = pageProbe.take(maxResults)
    return FakeReferencePage(
        references = references,
        nextOffset = Math.addExact(pageStart, references.size),
        hasMore = pageProbe.size > references.size,
    )
}

private fun FakeAnalysisBackend.issueReferenceContinuation(
    identity: FakeReferenceIdentity,
    continuation: FakeReferenceContinuation,
): ReferencePageToken = when (val issued = referenceContinuations.issue(identity, continuation)) {
    is ContinuationIssueResult.Issued -> issued.token
    is ContinuationIssueResult.Rejected -> throwReferenceContinuationFailure(issued.failure)
}

private fun FakeAnalysisBackend.throwReferenceContinuationFailure(failure: ContinuationAccessFailure): Nothing =
    if (failure == ContinuationAccessFailure.QueryMismatch) {
        throw ConflictException("Reference continuation token belongs to another query")
    } else {
        throw ConflictException("Unknown or consumed reference continuation token")
    }

private fun FakeAnalysisBackend.issueDiagnosticContinuation(
    identity: FakeDiagnosticIdentity,
    continuation: FakeDiagnosticContinuation,
): DiagnosticPageToken = when (val issued = diagnosticContinuations.issue(identity, continuation)) {
    is ContinuationIssueResult.Issued -> issued.token
    is ContinuationIssueResult.Rejected -> throwDiagnosticContinuationFailure(issued.failure)
}

private fun FakeAnalysisBackend.throwDiagnosticContinuationFailure(failure: ContinuationAccessFailure): Nothing =
    if (failure == ContinuationAccessFailure.QueryMismatch) {
        throw ConflictException("Diagnostic continuation token belongs to another query")
    } else {
        throw ConflictException("Unknown or consumed diagnostic continuation token")
    }

internal data class FakeReferenceIdentity(
    val filePath: String,
    val offset: Int,
    val includeDeclaration: Boolean,
    val includeUsageSiteScope: Boolean,
    val maxResults: Int,
)

internal data class FakeReferenceContinuation(
    var offset: Int,
) : ContinuationOwnedState()

internal data class FakeReferencePage(
    val references: List<Location>,
    val nextOffset: Int,
    val hasMore: Boolean,
) : ContinuationProjection() {
    fun toResult(
        declaration: Symbol?,
        totalCount: Int,
        nextPageToken: String?,
    ): ReferencesResult = ReferencesResult(
        declaration = declaration,
        references = references.map { location ->
            ReferenceOccurrence(
                location = location,
                containingSymbol = ContainingSymbolEvidence.TopLevel,
            )
        },
        evidence = if (hasMore) {
            RelationshipResultEvidence.Resumable(
                cardinality = ResultCardinality.KnownMinimum(
                    minOf(totalCount, Math.addExact(nextOffset, 1)),
                ),
                coverage = RelationshipSearchCoverage.resumable(),
            )
        } else {
            RelationshipResultEvidence.Complete(
                cardinality = ResultCardinality.Exact(totalCount),
                coverage = RelationshipSearchCoverage.complete(),
            )
        },
        page = if (hasMore) {
            PageInfo(
                truncated = true,
                nextPageToken = checkNotNull(nextPageToken),
            )
        } else {
            null
        },
    )
}

internal data class FakeDiagnosticIdentity(
    val filePaths: List<String>,
    val maxResults: Int,
)

internal data class FakeDiagnosticContinuation(
    val diagnostics: List<Diagnostic>,
    val fileStatuses: List<FileAnalysisStatus>,
    val fileHashes: List<FileHash>,
    var offset: Int,
) : ContinuationOwnedState() {
    fun page(maxResults: Int): FakeDiagnosticPage = FakeDiagnosticPage(
        diagnostics = diagnostics,
        fileStatuses = fileStatuses,
        fileHashes = fileHashes,
        pageOffset = offset,
        maxResults = maxResults,
    )
}

internal data class FakeDiagnosticPage(
    val diagnostics: List<Diagnostic>,
    val fileStatuses: List<FileAnalysisStatus>,
    val fileHashes: List<FileHash>,
    val pageOffset: Int,
    val maxResults: Int,
) : ContinuationProjection() {
    val nextOffset: Int = Math.addExact(
        pageOffset,
        minOf(maxResults, diagnostics.size - pageOffset),
    )
    val hasMore: Boolean = nextOffset < diagnostics.size

    fun toResult(nextPageToken: String?): DiagnosticsResult = DiagnosticsResult.paged(
        diagnostics = diagnostics,
        fileStatuses = fileStatuses,
        fileHashes = fileHashes,
        pageOffset = pageOffset,
        maxResults = maxResults,
        nextPageToken = nextPageToken,
    )
}
