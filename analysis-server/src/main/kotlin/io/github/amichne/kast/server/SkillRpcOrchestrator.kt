package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PageableResult
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.OutlineSymbol
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorOperationFamily
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastCallersRequest
import io.github.amichne.kast.api.contract.skill.KastCallersResponse
import io.github.amichne.kast.api.contract.skill.KastCandidate
import io.github.amichne.kast.api.contract.skill.KastDiscoverFailureResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoverQuery
import io.github.amichne.kast.api.contract.skill.KastDiscoverRequest
import io.github.amichne.kast.api.contract.skill.KastDiscoverResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoverSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoveryCandidate
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import io.github.amichne.kast.api.contract.skill.KastNextRequest
import io.github.amichne.kast.api.contract.skill.KastReferencesQuery
import io.github.amichne.kast.api.contract.skill.KastReferencesRequest
import io.github.amichne.kast.api.contract.skill.KastReferencesResponse
import io.github.amichne.kast.api.contract.skill.KastRenameByOffsetQuery
import io.github.amichne.kast.api.contract.skill.KastRenameByOffsetRequest
import io.github.amichne.kast.api.contract.skill.KastRenameBySymbolQuery
import io.github.amichne.kast.api.contract.skill.KastRenameBySymbolRequest
import io.github.amichne.kast.api.contract.skill.KastRenameFailureQuery
import io.github.amichne.kast.api.contract.skill.KastRenameFailureResponse
import io.github.amichne.kast.api.contract.skill.KastRenameQuery
import io.github.amichne.kast.api.contract.skill.KastRenameRequest
import io.github.amichne.kast.api.contract.skill.KastRenameResponse
import io.github.amichne.kast.api.contract.skill.KastRenameSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastResolveAmbiguousResponse
import io.github.amichne.kast.api.contract.skill.KastResolveFailureResponse
import io.github.amichne.kast.api.contract.skill.KastResolveNotFoundResponse
import io.github.amichne.kast.api.contract.skill.KastResolveContext
import io.github.amichne.kast.api.contract.skill.KastResolveParams
import io.github.amichne.kast.api.contract.skill.KastResolveQuery
import io.github.amichne.kast.api.contract.skill.KastResolveRequest
import io.github.amichne.kast.api.contract.skill.KastResolveResponse
import io.github.amichne.kast.api.contract.skill.KastResolveSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldFailureResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldQuery
import io.github.amichne.kast.api.contract.skill.KastScaffoldReferences
import io.github.amichne.kast.api.contract.skill.KastScaffoldRequest
import io.github.amichne.kast.api.contract.skill.KastScaffoldResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastScaffoldTypeHierarchy
import io.github.amichne.kast.api.contract.skill.KastSourceTextWindow
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateCreateFileQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateCreateFileRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateFailureQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateInsertAtOffsetQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateInsertAtOffsetRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateReplaceRangeQuery
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateReplaceRangeRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateRequest
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateResponse
import io.github.amichne.kast.api.contract.skill.KastWriteAndValidateSuccessResponse
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.api.contract.skill.WrapperNamedSymbolKind
import io.github.amichne.kast.api.contract.skill.WrapperScaffoldMode
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.api.contract.mutation.KastMutationProgressStage
import io.github.amichne.kast.server.mutation.MutationProgressEvent
import io.github.amichne.kast.server.mutation.MutationProgressReporter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal class SkillRpcOrchestrator(
    private val backend: AnalysisBackend,
    private val config: AnalysisServerConfig,
    private val json: Json,
) {
    private companion object {
        const val DEFAULT_DISCOVERY_SEARCH_LIMIT = 100
        const val EXACT_CARDINALITY_LIMIT = 2
        const val EXACT_CONSTRAINED_SEARCH_LIMIT = Int.MAX_VALUE
        const val MAX_SURROUNDING_LINES = 50
    }

    private data class ExactNamedSymbolCandidate(
        val ranked: RankedNamedSymbolCandidate,
        val resolvedConstraintSymbol: Symbol?,
    )

    private sealed interface SelectorSelection {
        sealed interface Selected : SelectorSelection {
            val selector: KastExactSymbolSelector
        }

        data class Explicit(
            override val selector: KastExactSymbolSelector,
        ) : Selected

        data class Handle(
            override val selector: KastExactSymbolSelector,
        ) : Selected

        data class Rejected(
            val reason: SelectorHandleAuthority.Resolution.RejectionReason,
        ) : SelectorSelection
    }

    suspend fun resolve(request: KastResolveRequest): KastResolveResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastResolveQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            includeDeclarationScope = request.includeDeclarationScope,
            includeDocumentation = request.includeDocumentation,
            surroundingLines = request.surroundingLines,
            includeSurroundingMembers = request.includeSurroundingMembers,
        )
        validateResolveQuery(query)
        val candidates = exactNamedSymbolCandidates(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            includeDeclarationScope = false,
        )
        if (candidates.isEmpty()) {
            return KastResolveNotFoundResponse(
                query = query,
                logFile = placeholderLogFile(),
            )
        }
        if (candidates.size > 1) {
            return KastResolveAmbiguousResponse(
                query = query,
                candidates = candidates.map { candidate ->
                    candidate.resolvedConstraintSymbol ?: candidate.ranked.symbol
                },
                logFile = placeholderLogFile(),
            )
        }
        val candidate = candidates.single()
        val resolved = resolveNamedSymbol(
            candidate = candidate.ranked,
            includeDeclarationScope = request.includeDeclarationScope,
            includeDocumentation = request.includeDocumentation,
        ) ?: return KastResolveNotFoundResponse(
            query = query,
            logFile = placeholderLogFile(),
        )
        val context = resolveContext(resolved.symbol, request)
        return KastResolveSuccessResponse(
            query = query,
            symbol = resolved.symbol,
            selectorHandle = issueSelectorHandle(resolved.symbol),
            filePath = resolved.filePath,
            offset = resolved.offset,
            candidate = KastCandidate(
                line = resolved.symbol.location.startLine,
                column = resolved.symbol.location.startColumn,
                context = resolved.symbol.location.preview,
            ),
            candidateCount = 1,
            alternatives = emptyList(),
            context = context,
            logFile = placeholderLogFile(),
        )
    }

    suspend fun selectorIdentity(
        request: KastSelectorIdentityRequest,
    ): KastSelectorIdentityResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        return when (
            val resolution = backend.selectorHandles.resolve(
                handle = request.selectorHandle,
                workspaceRoot = workspaceRoot,
                family = request.family,
            )
        ) {
            is SelectorHandleAuthority.Resolution.Resolved -> KastSelectorIdentityAvailableResponse(
                resolution.selector.normalizedFor(workspaceRoot).toHandleSubject(),
            )
            is SelectorHandleAuthority.Resolution.Rejected ->
                KastSelectorHandleRejectedResponse(resolution.reason)
        }
    }

    suspend fun discover(request: KastDiscoverRequest): KastDiscoverResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val query = KastDiscoverQuery(
            workspaceRoot = workspaceRoot,
            symbol = request.symbol,
            fileHint = request.fileHint,
            line = request.line,
            codeSnippet = request.codeSnippet,
            kind = request.kind,
            containingType = request.containingType,
            maxResults = request.maxResults,
            includeDeclarationScope = request.includeDeclarationScope,
        )
        validateDiscoverQuery(query)
        val searchLimit = minOf(
            config.maxResults,
            maxOf(request.maxResults + 1, DEFAULT_DISCOVERY_SEARCH_LIMIT),
        )
        val candidates = rankedNamedSymbolCandidates(
            symbolName = request.symbol,
            fileHint = request.fileHint,
            kind = request.kind,
            containingType = request.containingType,
            line = request.line,
            codeSnippet = request.codeSnippet,
            includeDeclarationScope = request.includeDeclarationScope,
            searchLimit = searchLimit,
        )
        val visibleCandidates = candidates.take(request.maxResults)
        return KastDiscoverSuccessResponse(
            query = query,
            candidates = visibleCandidates.mapIndexed { index, candidate ->
                candidate.toDiscoveryCandidate(
                    rank = index + 1,
                    workspaceRoot = workspaceRoot,
                    requestedSymbol = request.symbol,
                )
            },
            page = if (candidates.size > visibleCandidates.size) {
                PageInfo(
                    truncated = true,
                    nextPageToken = visibleCandidates.size.toString(),
                )
            } else {
                null
            },
            logFile = placeholderLogFile(),
        )
    }

    suspend fun references(request: KastReferencesRequest): KastReferencesResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val selected = when (
            val selection = selectSelector(
                explicitSelector = request.selector,
                selectorHandle = request.selectorHandle,
                workspaceRoot = workspaceRoot,
                family = SelectorOperationFamily.REFERENCES,
            )
        ) {
            is SelectorSelection.Rejected ->
                return KastSelectorHandleRejectedResponse(selection.reason)
            is SelectorSelection.Selected -> selection
        }
        val selector = selected.selector
        val query = KastReferencesQuery(
            workspaceRoot = workspaceRoot,
            selector = selector,
            includeDeclaration = request.includeDeclaration,
            includeUsageSiteScope = request.includeUsageSiteScope,
            maxResults = request.maxResults,
            pageToken = request.pageToken,
        )
        validateReferencesQuery(query)
        val subject = when (selected) {
            is SelectorSelection.Explicit -> {
                requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
                val resolved = try {
                    backend.resolveSymbol(
                        SymbolQuery(
                            position = FilePosition(
                                filePath = selector.declarationFile,
                                offset = selector.declarationStartOffset,
                            ),
                        ).parsed(),
                    ).symbol
                } catch (_: NotFoundException) {
                    return KastReferencesSubjectNotFoundResponse(selector)
                }
                resolved.toSymbolIdentity()
            }
            is SelectorSelection.Handle -> selector.toHandleSubject()
        }
        if (!selector.matches(subject)) {
            return KastReferencesSubjectIdentityMismatchResponse(selector, subject)
        }
        if (subject.kind == SymbolKind.UNKNOWN) {
            return KastReferencesUnsupportedSubjectKindResponse(selector, subject)
        }
        requireReadCapability(ReadCapability.FIND_REFERENCES)
        val completeResult = try {
            backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = selector.declarationFile,
                        offset = selector.declarationStartOffset,
                    ),
                    includeDeclaration = request.includeDeclaration,
                    includeUsageSiteScope = request.includeUsageSiteScope,
                    maxResults = request.maxResults,
                    pageToken = request.pageToken,
                    selector = selector,
                ).parsed(),
            )
        } catch (_: TimeoutCancellationException) {
            return KastReferencesDegradedResponse(
                selector,
                subject,
                KastReferencesDegradedReason.TIMEOUT,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.TIMED_OUT),
            )
        } catch (failure: CancellationException) {
            if (!currentCoroutineContext().isActive) throw failure
            return KastReferencesDegradedResponse(
                selector,
                subject,
                KastReferencesDegradedReason.CANCELLED,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.CANCELLED),
            )
        } catch (failure: ConflictException) {
            return when (failure.details["continuationFailure"]) {
                "generationChanged" -> KastReferencesCursorStaleResponse(
                    selector,
                    RelationCursorStaleReason.GENERATION_CHANGED,
                    limitedRelationshipEvidence(
                        0,
                        RelationshipSearchLimitation.GENERATION_CHANGED,
                    ),
                )
                "expired" -> KastReferencesCursorStaleResponse(
                    selector,
                    RelationCursorStaleReason.EXPIRED,
                    limitedRelationshipEvidence(
                        0,
                        RelationshipSearchLimitation.CONTINUATION_EXPIRED,
                    ),
                )
                "queryMismatch" -> KastReferencesCursorInvalidResponse(
                    selector,
                    RelationCursorInvalidReason.QUERY_MISMATCH,
                    limitedRelationshipEvidence(
                        0,
                        RelationshipSearchLimitation.CONTINUATION_INVALID,
                    ),
                )
                "boundSourceUnavailable" -> KastReferencesDegradedResponse(
                    selector,
                    subject,
                    KastReferencesDegradedReason.BOUND_SOURCE_UNAVAILABLE,
                    limitedRelationshipEvidence(
                        0,
                        RelationshipSearchLimitation.BACKEND_UNAVAILABLE,
                    ),
                )
                "indexIdentityUnavailable" -> KastReferencesDegradedResponse(
                    selector,
                    subject,
                    KastReferencesDegradedReason.INDEX_IDENTITY_UNAVAILABLE,
                    limitedRelationshipEvidence(
                        0,
                        RelationshipSearchLimitation.BACKEND_INCOMPLETE,
                    ),
                )
                else -> KastReferencesCursorInvalidResponse(
                    selector,
                    RelationCursorInvalidReason.UNKNOWN_HANDLE,
                    limitedRelationshipEvidence(
                        0,
                        RelationshipSearchLimitation.CONTINUATION_INVALID,
                    ),
                )
            }
        }
        val evidence = if (
            completeResult.searchScope?.candidateCoverage == SearchScope.CandidateCoverage.PARTIAL &&
            completeResult.evidence !is RelationshipResultEvidence.Limited
        ) {
            limitedRelationshipEvidence(
                completeResult.evidence.cardinality.knownMinimum(),
                RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
            )
        } else {
            completeResult.evidence
        }
        val availableEvidence: RelationshipResultEvidence.Available = when (evidence) {
            is RelationshipResultEvidence.Complete -> evidence
            is RelationshipResultEvidence.Resumable -> evidence
            is RelationshipResultEvidence.Limited -> return KastReferencesDegradedResponse(
                selector = selector,
                subject = subject,
                reason = KastReferencesDegradedReason.REFERENCES_UNAVAILABLE,
                evidence = evidence,
            )
        }
        return KastReferencesAvailableResponse(
            subject = subject,
            references = completeResult.references,
            evidence = availableEvidence,
            page = completeResult.page,
            searchScope = completeResult.searchScope,
            declaration = completeResult.declaration,
        )
    }

    suspend fun callers(request: KastCallersRequest): KastCallersResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val family = when (request.direction) {
            WrapperCallDirection.INCOMING -> SelectorOperationFamily.CALLERS
            WrapperCallDirection.OUTGOING -> SelectorOperationFamily.CALLEES
        }
        val selected = when (
            val selection = selectSelector(
                explicitSelector = request.selector,
                selectorHandle = request.selectorHandle,
                workspaceRoot = workspaceRoot,
                family = family,
            )
        ) {
            is SelectorSelection.Rejected ->
                return KastSelectorHandleRejectedResponse(selection.reason)
            is SelectorSelection.Selected -> selection
        }
        val selector = selected.selector
        val query = KastCallersQuery(
            workspaceRoot = workspaceRoot,
            selector = selector,
            direction = request.direction,
            depth = request.depth,
            maxResults = request.maxResults,
            pageToken = request.pageToken,
        )
        validateRelationshipQuery(selector, request.depth, request.maxResults)
        val subject = selected.resolveSubject()
            ?: return KastCallersSubjectNotFoundResponse(selector)
        if (!selector.matches(subject)) {
            return KastCallersSubjectIdentityMismatchResponse(selector, subject)
        }
        if (subject.kind != SymbolKind.FUNCTION) {
            return KastCallersUnsupportedSubjectKindResponse(selector, subject)
        }
        if (ReadCapability.CALL_HIERARCHY !in backend.capabilities().readCapabilities) {
            return KastCallersDegradedResponse(
                selector,
                subject,
                KastCallDegradedReason.CALL_HIERARCHY_UNAVAILABLE,
                limitedRelationshipEvidence(
                    0,
                    RelationshipSearchLimitation.BACKEND_UNAVAILABLE,
                ),
            )
        }
        val result = try {
            backend.callRelations(query)
        } catch (_: TimeoutCancellationException) {
            return KastCallersDegradedResponse(
                selector,
                subject,
                KastCallDegradedReason.TIMEOUT,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.TIMED_OUT),
            )
        } catch (failure: CancellationException) {
            if (!currentCoroutineContext().isActive) throw failure
            return KastCallersDegradedResponse(
                selector,
                subject,
                KastCallDegradedReason.CANCELLED,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.CANCELLED),
            )
        } catch (failure: ConflictException) {
            return callContinuationOutcome(selector, subject, failure)
        }
        return when (result) {
            is CallRelationsResult.Available -> KastCallersAvailableResponse(
                subject = subject,
                records = result.records,
                page = result.page,
            )
            is CallRelationsResult.Limited -> KastCallersDegradedResponse(
                selector = selector,
                subject = subject,
                reason = KastCallDegradedReason.CALL_HIERARCHY_UNAVAILABLE,
                evidence = result.evidence,
            )
        }
    }

    suspend fun implementations(
        request: KastImplementationsRequest,
    ): KastImplementationsResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val selected = when (
            val selection = selectSelector(
                explicitSelector = request.selector,
                selectorHandle = request.selectorHandle,
                workspaceRoot = workspaceRoot,
                family = SelectorOperationFamily.IMPLEMENTATIONS,
            )
        ) {
            is SelectorSelection.Rejected ->
                return KastSelectorHandleRejectedResponse(selection.reason)
            is SelectorSelection.Selected -> selection
        }
        val selector = selected.selector
        val query = KastImplementationsQuery(
            workspaceRoot = workspaceRoot,
            selector = selector,
            maxResults = request.maxResults,
            pageToken = request.pageToken,
        )
        validateRelationshipQuery(selector, null, request.maxResults)
        val subject = selected.resolveSubject()
            ?: return KastImplementationsSubjectNotFoundResponse(selector)
        if (!selector.matches(subject)) {
            return KastImplementationsSubjectIdentityMismatchResponse(selector, subject)
        }
        if (subject.kind !in setOf(SymbolKind.CLASS, SymbolKind.INTERFACE)) {
            return KastImplementationsUnsupportedSubjectKindResponse(selector, subject)
        }
        if (ReadCapability.IMPLEMENTATIONS !in backend.capabilities().readCapabilities) {
            return KastImplementationsDegradedResponse(
                selector,
                subject,
                KastImplementationsDegradedReason.IMPLEMENTATIONS_UNAVAILABLE,
                limitedRelationshipEvidence(
                    0,
                    RelationshipSearchLimitation.BACKEND_UNAVAILABLE,
                ),
            )
        }
        val result = try {
            backend.implementationRelations(query)
        } catch (_: TimeoutCancellationException) {
            return KastImplementationsDegradedResponse(
                selector,
                subject,
                KastImplementationsDegradedReason.TIMEOUT,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.TIMED_OUT),
            )
        } catch (failure: CancellationException) {
            if (!currentCoroutineContext().isActive) throw failure
            return KastImplementationsDegradedResponse(
                selector,
                subject,
                KastImplementationsDegradedReason.CANCELLED,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.CANCELLED),
            )
        } catch (failure: ConflictException) {
            return implementationContinuationOutcome(selector, subject, failure)
        }
        return when (result) {
            is ImplementationRelationsResult.Available ->
                KastImplementationsAvailableResponse(subject, result.records, result.page)
            is ImplementationRelationsResult.Limited -> KastImplementationsDegradedResponse(
                selector = selector,
                subject = subject,
                reason = KastImplementationsDegradedReason.IMPLEMENTATIONS_UNAVAILABLE,
                evidence = result.evidence,
            )
        }
    }

    suspend fun hierarchy(request: KastHierarchyRequest): KastHierarchyResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val selected = when (
            val selection = selectSelector(
                explicitSelector = request.selector,
                selectorHandle = request.selectorHandle,
                workspaceRoot = workspaceRoot,
                family = SelectorOperationFamily.HIERARCHY,
            )
        ) {
            is SelectorSelection.Rejected ->
                return KastSelectorHandleRejectedResponse(selection.reason)
            is SelectorSelection.Selected -> selection
        }
        val selector = selected.selector
        val query = KastHierarchyQuery(
            workspaceRoot = workspaceRoot,
            selector = selector,
            direction = request.direction,
            depth = request.depth,
            maxResults = request.maxResults,
            pageToken = request.pageToken,
        )
        validateRelationshipQuery(selector, request.depth, request.maxResults)
        val subject = selected.resolveSubject()
            ?: return KastHierarchySubjectNotFoundResponse(selector)
        if (!selector.matches(subject)) {
            return KastHierarchySubjectIdentityMismatchResponse(selector, subject)
        }
        if (subject.kind !in setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT)) {
            return KastHierarchyUnsupportedSubjectKindResponse(selector, subject)
        }
        if (ReadCapability.TYPE_HIERARCHY !in backend.capabilities().readCapabilities) {
            return KastHierarchyDegradedResponse(
                selector,
                subject,
                KastHierarchyDegradedReason.TYPE_HIERARCHY_UNAVAILABLE,
                limitedRelationshipEvidence(
                    0,
                    RelationshipSearchLimitation.BACKEND_UNAVAILABLE,
                ),
            )
        }
        val result = try {
            backend.hierarchyRelations(query)
        } catch (_: TimeoutCancellationException) {
            return KastHierarchyDegradedResponse(
                selector,
                subject,
                KastHierarchyDegradedReason.TIMEOUT,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.TIMED_OUT),
            )
        } catch (failure: CancellationException) {
            if (!currentCoroutineContext().isActive) throw failure
            return KastHierarchyDegradedResponse(
                selector,
                subject,
                KastHierarchyDegradedReason.CANCELLED,
                limitedRelationshipEvidence(0, RelationshipSearchLimitation.CANCELLED),
            )
        } catch (failure: ConflictException) {
            return hierarchyContinuationOutcome(selector, subject, failure)
        }
        return when (result) {
            is HierarchyRelationsResult.Available ->
                KastHierarchyAvailableResponse(subject, result.records, result.page)
            is HierarchyRelationsResult.Limited -> KastHierarchyDegradedResponse(
                selector = selector,
                subject = subject,
                reason = KastHierarchyDegradedReason.TYPE_HIERARCHY_UNAVAILABLE,
                evidence = result.evidence,
            )
        }
    }

    suspend fun scaffold(request: KastScaffoldRequest): KastScaffoldResponse {
        val workspaceRoot = workspaceRootFor(request.workspaceRoot)
        val targetFile = request.targetFile.normalizedAbsolutePath()
        val query = KastScaffoldQuery(
            workspaceRoot = workspaceRoot,
            targetFile = request.targetFile,
            targetSymbol = request.targetSymbol,
            mode = request.mode,
            kind = request.kind,
        )
        requireReadCapability(ReadCapability.FILE_OUTLINE)
        val outline = backend.fileOutline(FileOutlineQuery(filePath = targetFile).parsed()).symbols
        val resolvedSymbol = request.targetSymbol?.let { symbolName ->
            resolveNamedSymbol(
                symbolName = symbolName,
                fileHint = request.targetFile,
                kind = request.kind,
                containingType = null,
            )
        }
        val references = resolvedSymbol?.let { resolved ->
            requireReadCapability(ReadCapability.FIND_REFERENCES)
            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                    includeDeclaration = true,
                    maxResults = config.maxResults,
                ).parsed(),
            )
            KastScaffoldReferences(
                locations = result.references,
                count = result.references.size,
                cardinality = result.cardinality,
                page = result.page,
                searchScope = result.searchScope,
                declaration = result.declaration,
            )
        }
        val typeHierarchy = resolvedSymbol?.takeIf { it.symbol.kind in setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT) }?.let { resolved ->
            requireReadCapability(ReadCapability.TYPE_HIERARCHY)
            val result = backend.typeHierarchy(
                TypeHierarchyQuery(
                    position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                ).parsed(),
            )
            KastScaffoldTypeHierarchy(root = result.root, stats = result.stats)
        }
        val insertionPoint = resolvedSymbol?.let { resolved ->
            requireReadCapability(ReadCapability.SEMANTIC_INSERTION_POINT)
            backend.semanticInsertionPoint(
                io.github.amichne.kast.api.contract.SemanticInsertionQuery(
                    position = FilePosition(filePath = resolved.filePath, offset = resolved.offset),
                    target = request.mode.toInsertionTarget(),
                ).parsed(),
            )
        }
        val fileContent = targetFile.readTextIfPresent()
        return KastScaffoldSuccessResponse(
            query = query,
            outline = outline,
            fileContent = fileContent,
            symbol = resolvedSymbol?.symbol,
            references = references,
            typeHierarchy = typeHierarchy,
            insertionPoint = insertionPoint,
            logFile = placeholderLogFile(),
        )
    }

    suspend fun rename(
        request: KastRenameRequest,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
    ): KastRenameResponse {
        progress.enter(KastMutationProgressStage.IDENTITY_RESOLUTION)
        return when (request) {
            is KastRenameBySymbolRequest -> renameBySymbol(request, progress)
            is KastRenameByOffsetRequest -> renameByOffset(request, progress)
            is KastRenameBySelectorHandleRequest -> renameBySelectorHandle(request, progress)
        }
    }

    suspend fun writeAndValidate(request: KastWriteAndValidateRequest): KastWriteAndValidateResponse = when (request) {
        is KastWriteAndValidateCreateFileRequest -> writeAndValidateCreate(request)
        is KastWriteAndValidateInsertAtOffsetRequest -> writeAndValidateInsert(request)
        is KastWriteAndValidateReplaceRangeRequest -> writeAndValidateReplace(request)
    }

    suspend fun addFile(
        request: KastAddFileRequest,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
    ): KastScopeMutationResponse {
        val filePath = request.targetFilePath.value
        return writeAndValidateCreate(
            KastWriteAndValidateCreateFileRequest(
                workspaceRoot = request.requestedWorkspaceRoot?.value,
                filePath = filePath,
                contentFile = request.contentFilePath.value,
            ),
            progress,
        ).toScopeMutationResponse(
            operation = request.operation,
            affectedFiles = listOf(filePath),
            createdFiles = listOf(filePath),
            editCount = 1,
        )
    }

    suspend fun addDeclaration(
        request: KastAddDeclarationRequest,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
    ): KastScopeMutationResponse = addPlacedContent(request, progress)

    suspend fun addImplementation(
        request: KastAddImplementationRequest,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
    ): KastScopeMutationResponse = addPlacedContent(request, progress)

    suspend fun addStatement(
        request: KastAddStatementRequest,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
    ): KastScopeMutationResponse {
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
            progress = progress,
        )
    }

    suspend fun replaceDeclaration(
        request: KastReplaceDeclarationRequest,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
    ): KastScopeMutationResponse = when (request) {
        is KastReplaceDeclarationBySymbolRequest -> replaceDeclarationBySymbol(request, progress)
        is KastReplaceDeclarationBySelectorHandleRequest -> replaceDeclarationBySelectorHandle(request, progress)
    }

    private suspend fun replaceDeclarationBySymbol(
        request: KastReplaceDeclarationBySymbolRequest,
        progress: MutationProgressReporter,
    ): KastScopeMutationResponse {
        val workspaceRoot = workspaceRootFor(request.requestedWorkspaceRoot?.value)
        val symbol = request.requestedSymbol.value
        progress.enter(KastMutationProgressStage.IDENTITY_RESOLUTION)
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
            progress = progress,
        )
    }

    private suspend fun replaceDeclarationBySelectorHandle(
        request: KastReplaceDeclarationBySelectorHandleRequest,
        progress: MutationProgressReporter,
    ): KastScopeMutationResponse {
        val workspaceRoot = workspaceRootFor(request.requestedWorkspaceRoot?.value)
        progress.enter(KastMutationProgressStage.IDENTITY_RESOLUTION)
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
            progress = progress,
        )
    }

    private suspend fun replaceResolvedDeclaration(
        operation: KastScopeMutationOperation,
        workspaceRoot: String,
        contentFile: String,
        subject: String,
        filePath: String,
        symbol: Symbol,
        progress: MutationProgressReporter,
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
            progress = progress,
        )
        return response.toScopeMutationResponse(
            operation = operation,
            affectedFiles = listOf(filePath),
            editCount = 1,
        )
    }

    private suspend fun renameBySymbol(
        request: KastRenameBySymbolRequest,
        progress: MutationProgressReporter,
    ): KastRenameResponse {
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
            progress = progress,
        )
    }

    private suspend fun renameByOffset(
        request: KastRenameByOffsetRequest,
        progress: MutationProgressReporter,
    ): KastRenameResponse {
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
            progress = progress,
        )
    }

    private suspend fun renameBySelectorHandle(
        request: KastRenameBySelectorHandleRequest,
        progress: MutationProgressReporter,
    ): KastRenameResponse {
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
            progress = progress,
        )
    }

    private suspend fun performRename(
        filePath: String,
        offset: Int,
        newName: String,
        queryBuilder: () -> KastRenameQuery,
        failureQueryBuilder: () -> KastRenameFailureQuery,
        progress: MutationProgressReporter,
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
        progress.awaitPathAdmission(renameResult.affectedFiles)
        progress.enter(KastMutationProgressStage.EDIT_APPLICATION)
        val applyResult = backend.applyEdits(
            ApplyEditsQuery(
                edits = renameResult.edits,
                fileHashes = renameResult.fileHashes,
            ).parsed(),
        )
        progress.editApplicationCompleted()
        currentCoroutineContext().ensureActive()
        progress.enter(KastMutationProgressStage.WORKSPACE_REFRESH)
        val diagnosticsSummary = if (renameResult.affectedFiles.isEmpty()) {
            KastDiagnosticsSummary.completeWithoutFiles()
        } else {
            val admission = awaitSemanticAdmission(renameResult.affectedFiles)
            if (admission.clean) {
                progress.enter(KastMutationProgressStage.DIAGNOSTICS)
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

    private suspend fun writeAndValidateCreate(
        request: KastWriteAndValidateCreateFileRequest,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
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
        progress.awaitPathAdmission(listOf(filePath))
        progress.enter(KastMutationProgressStage.EDIT_APPLICATION)
        val applyResult = backend.applyEdits(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(FileOperation.CreateFile(filePath = filePath, content = content)),
            ).parsed(),
        )
        progress.editApplicationCompleted()
        currentCoroutineContext().ensureActive()
        progress.enter(KastMutationProgressStage.WORKSPACE_REFRESH)
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
        progress.enter(KastMutationProgressStage.IMPORT_OPTIMIZATION)
        val optimized = optimizeImports(filePath)
        progress.enter(KastMutationProgressStage.DIAGNOSTICS)
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

    private suspend fun writeAndValidateInsert(request: KastWriteAndValidateInsertAtOffsetRequest): KastWriteAndValidateResponse {
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

    private suspend fun writeAndValidateReplace(request: KastWriteAndValidateReplaceRangeRequest): KastWriteAndValidateResponse {
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

    private suspend fun applyEditsAndValidate(
        filePath: String,
        edits: List<TextEdit>,
        query: KastWriteAndValidateQuery,
        progress: MutationProgressReporter = MutationProgressReporter.NONE,
    ): KastWriteAndValidateResponse {
        requireCapabilities(
            readCapabilities = setOf(ReadCapability.DIAGNOSTICS),
            mutationCapabilities = setOf(
                MutationCapability.APPLY_EDITS,
                MutationCapability.REFRESH_WORKSPACE,
                MutationCapability.OPTIMIZE_IMPORTS,
            ),
        )
        progress.awaitPathAdmission(listOf(filePath))
        progress.enter(KastMutationProgressStage.EDIT_APPLICATION)
        val applyResult = backend.applyEdits(
            ApplyEditsQuery(
                edits = edits,
                fileHashes = currentFileHashes(edits.map(TextEdit::filePath)),
            ).parsed(),
        )
        progress.editApplicationCompleted()
        currentCoroutineContext().ensureActive()
        progress.enter(KastMutationProgressStage.WORKSPACE_REFRESH)
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
        progress.enter(KastMutationProgressStage.IMPORT_OPTIMIZATION)
        val optimized = optimizeImports(filePath)
        progress.enter(KastMutationProgressStage.DIAGNOSTICS)
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

    private suspend fun addPlacedContent(
        request: KastPlacedScopeMutationRequest,
        progress: MutationProgressReporter,
    ): KastScopeMutationResponse =
        addContentAtPlacement(
            operation = request.operation,
            workspaceRoot = request.requestedWorkspaceRoot?.value,
            placement = request.placement,
            contentFile = request.contentFilePath.value,
            statementBody = false,
            progress = progress,
        )

    private suspend fun addContentAtPlacement(
        operation: KastScopeMutationOperation,
        workspaceRoot: String?,
        placement: KastPlacementSelector,
        contentFile: String,
        statementBody: Boolean,
        progress: MutationProgressReporter,
    ): KastScopeMutationResponse {
        workspaceRootFor(workspaceRoot)
        progress.enter(KastMutationProgressStage.IDENTITY_RESOLUTION)
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
            progress = progress,
        )
        return response.toScopeMutationResponse(
            operation = operation,
            affectedFiles = listOf(resolvedPlacement.filePath),
            editCount = 1,
            placement = resolvedPlacement,
        )
    }

    private suspend fun resolvePlacement(
        placement: KastPlacementSelector,
        statementBody: Boolean,
    ): KastResolvedPlacement {
        val filePath = placement.scope.filePathForPlacement()
        val offset = when (val anchor = placement.anchor) {
            is KastAtPlacementAnchor -> placement.scope.offsetForAnchor(anchor.anchor, statementBody)
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

    private suspend fun KastPlacementScopeSelector.filePathForPlacement(): String = when (this) {
        is KastFilePlacementScope -> insideFile.normalizedAbsolutePath()
        is KastNamedPlacementScope -> resolveSymbolForPlacement(insideScope, fileHint, kind, containingType).filePath
    }

    private suspend fun KastPlacementScopeSelector.offsetForAnchor(
        anchor: KastPlacementAnchor,
        statementBody: Boolean,
    ): Int = when (this) {
        is KastFilePlacementScope -> fileOffsetForAnchor(insideFile.normalizedAbsolutePath(), anchor)
        is KastNamedPlacementScope -> {
            val resolved = resolveSymbolForPlacement(insideScope, fileHint, kind, containingType)
            if (statementBody) {
                executableBodyOffset(resolved, anchor)
            } else {
                symbolOffsetForAnchor(resolved, anchor)
            }
        }
    }

    private suspend fun fileOffsetForAnchor(filePath: String, anchor: KastPlacementAnchor): Int = when (anchor) {
        KastPlacementAnchor.FILE_TOP -> 0
        KastPlacementAnchor.FILE_BOTTOM -> Files.readString(Path.of(filePath)).length
        KastPlacementAnchor.AFTER_IMPORTS -> semanticInsertionOffset(filePath, 0, SemanticInsertionTarget.AFTER_IMPORTS)
        KastPlacementAnchor.BODY_START,
        KastPlacementAnchor.BODY_END -> throw ValidationException("$anchor requires --inside-scope")
    }

    private suspend fun symbolOffsetForAnchor(
        resolved: ResolvedNamedSymbol,
        anchor: KastPlacementAnchor,
    ): Int = when (anchor) {
        KastPlacementAnchor.BODY_START -> semanticInsertionOffset(resolved.filePath, resolved.offset, SemanticInsertionTarget.CLASS_BODY_START)
        KastPlacementAnchor.BODY_END -> semanticInsertionOffset(resolved.filePath, resolved.offset, SemanticInsertionTarget.CLASS_BODY_END)
        KastPlacementAnchor.FILE_TOP -> 0
        KastPlacementAnchor.FILE_BOTTOM -> Files.readString(Path.of(resolved.filePath)).length
        KastPlacementAnchor.AFTER_IMPORTS -> semanticInsertionOffset(resolved.filePath, 0, SemanticInsertionTarget.AFTER_IMPORTS)
    }

    private fun executableBodyOffset(
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

    private suspend fun semanticInsertionOffset(
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

    private suspend fun resolveSymbolForPlacement(
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

    private fun requireAnchorInPlacementFile(
        placementFilePath: String,
        resolvedAnchor: ResolvedNamedSymbol,
    ) {
        if (resolvedAnchor.filePath != placementFilePath) {
            throw ValidationException(
                "Anchor symbol '${resolvedAnchor.symbol.fqName}' resolved in ${resolvedAnchor.filePath}, outside placement file $placementFilePath",
            )
        }
    }

    private fun ResolvedNamedSymbol.declarationStartOffset(): Int =
        symbol.declarationScope?.startOffset ?: symbol.location.startOffset

    private fun ResolvedNamedSymbol.declarationEndOffset(): Int =
        symbol.declarationScope?.endOffset ?: symbol.location.endOffset

    private fun KastStatementPlacementAnchor.toPlacementAnchor(): KastPlacementAnchor = when (this) {
        KastStatementPlacementAnchor.BODY_END -> KastPlacementAnchor.BODY_END
    }

    private fun currentFileHashes(filePaths: List<String>): List<FileHash> =
        filePaths.distinct().map { filePath ->
            FileHash(
                filePath = filePath,
                hash = FileHashing.sha256(Files.readString(Path.of(filePath))),
            )
        }

    private suspend fun optimizeImports(filePath: String) = run {
        requireMutationCapability(MutationCapability.OPTIMIZE_IMPORTS)
        backend.optimizeImports(ImportOptimizeQuery(filePaths = listOf(filePath)).parsed())
    }

    private suspend fun awaitSemanticAdmission(filePaths: List<String>): KastDiagnosticsSummary {
        requireMutationCapability(MutationCapability.REFRESH_WORKSPACE)
        return KastDiagnosticsSummary.from(
            backend.refresh(RefreshQuery(filePaths = filePaths.distinct()).parsed()),
        )
    }

    private suspend fun validateFiles(filePaths: List<String>): KastDiagnosticsSummary {
        requireReadCapability(ReadCapability.DIAGNOSTICS)
        return KastDiagnosticsSummary.from(
            result = backend.diagnostics(DiagnosticsQuery(filePaths = filePaths).parsed()),
            maxReturnedErrors = PositiveInt(config.maxResults),
        )
    }

    private fun KastWriteAndValidateResponse.toScopeMutationResponse(
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

    private suspend fun resolveNamedSymbol(
        symbolName: String,
        fileHint: String? = null,
        kind: WrapperNamedSymbolKind? = null,
        containingType: String? = null,
        includeDeclarationScope: Boolean = false,
    ): ResolvedNamedSymbol? {
        val candidates = rankedNamedSymbolCandidates(
            symbolName = symbolName,
            fileHint = fileHint,
            kind = kind,
            containingType = containingType,
            line = null,
            codeSnippet = null,
            includeDeclarationScope = includeDeclarationScope,
            searchLimit = minOf(config.maxResults, DEFAULT_DISCOVERY_SEARCH_LIMIT),
        )
        val best = candidates.firstOrNull() ?: return null
        val resolved = resolveNamedSymbol(
            candidate = best,
            includeDeclarationScope = includeDeclarationScope,
            includeDocumentation = false,
        ) ?: return null
        val alternativeFqNames = candidates
            .asSequence()
            .map { it.symbol.fqName }
            .filter { it != best.symbol.fqName }
            .distinct()
            .take(3)
            .toList()
        return resolved.copy(
            candidateCount = candidates.size,
            alternativeFqNames = alternativeFqNames,
        )
    }

    private suspend fun resolveNamedSymbol(
        candidate: RankedNamedSymbolCandidate,
        includeDeclarationScope: Boolean,
        includeDocumentation: Boolean,
    ): ResolvedNamedSymbol? {
        requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
        val resolved = backend.resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = candidate.symbol.location.filePath,
                    offset = candidate.symbol.location.startOffset,
                ),
                includeDeclarationScope = includeDeclarationScope,
                includeDocumentation = includeDocumentation,
            ).parsed(),
        )
        return ResolvedNamedSymbol(
            symbol = resolved.symbol,
            filePath = candidate.symbol.location.filePath,
            offset = candidate.symbol.location.startOffset,
            candidateCount = 1,
            alternativeFqNames = emptyList(),
        )
    }

    private suspend fun rankedNamedSymbolCandidates(
        symbolName: String,
        fileHint: String?,
        kind: WrapperNamedSymbolKind?,
        containingType: String?,
        line: Int?,
        codeSnippet: String?,
        includeDeclarationScope: Boolean,
        searchLimit: Int,
    ): List<RankedNamedSymbolCandidate> {
        requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
        val symbols = symbolSearchPatterns(symbolName)
            .flatMap { pattern ->
                backend.workspaceSymbolSearch(
                    WorkspaceSymbolQuery(
                        pattern = pattern,
                        maxResults = searchLimit,
                        includeDeclarationScope = includeDeclarationScope,
                    ).parsed(),
                ).withLimit(searchLimit) { workspaceSymbolPageToken(searchLimit) }.symbols
            }
            .distinctBy { symbol -> Triple(symbol.fqName, symbol.location.filePath, symbol.location.startOffset) }
        val filteredSymbols = if (symbolName.contains('.')) {
            symbols.filter { symbol -> symbol.fqName == symbolName }
        } else {
            symbols
        }

        return filteredSymbols
            .asSequence()
            .map { candidate ->
                rankCandidate(
                    candidate = candidate,
                    requestedSymbol = symbolName,
                    fileHint = fileHint,
                    kind = kind,
                    containingType = containingType,
                    line = line,
                    codeSnippet = codeSnippet,
                )
            }
            .sortedWith(
                compareByDescending<RankedNamedSymbolCandidate> { it.score }
                    .thenBy { it.symbol.location.filePath }
                    .thenBy { it.symbol.location.startLine }
                    .thenBy { it.symbol.fqName },
            )
            .toList()
    }

    private suspend fun exactNamedSymbolCandidates(
        symbolName: String,
        fileHint: String?,
        kind: WrapperNamedSymbolKind?,
        containingType: String?,
        includeDeclarationScope: Boolean,
    ): List<ExactNamedSymbolCandidate> {
        requireReadCapability(ReadCapability.WORKSPACE_SYMBOL_SEARCH)
        val searchLimit = if (fileHint == null && containingType == null) {
            EXACT_CARDINALITY_LIMIT
        } else {
            EXACT_CONSTRAINED_SEARCH_LIMIT
        }
        val rankedCandidates = symbolSearchPatterns(symbolName)
            .flatMap { pattern ->
                backend.workspaceSymbolSearch(
                    WorkspaceSymbolQuery(
                        pattern = exactWorkspaceSymbolPattern(pattern),
                        kind = kind?.toSymbolKind(),
                        maxResults = searchLimit,
                        regex = true,
                        includeDeclarationScope = includeDeclarationScope,
                    ).parsed(),
                ).withLimit(searchLimit) { workspaceSymbolPageToken(searchLimit) }.symbols
            }
            .distinctBy { symbol -> Triple(symbol.fqName, symbol.location.filePath, symbol.location.startOffset) }
            .asSequence()
            .filter { symbol -> exactIdentityMatches(symbolName, symbol.fqName) }
            .filter { symbol -> kind == null || symbol.kind == kind.toSymbolKind() }
            .filter { symbol -> fileHint == null || exactFileHintMatches(fileHint, symbol.location.filePath) }
            .sortedWith(
                compareBy<Symbol> { it.location.filePath }
                    .thenBy { it.location.startOffset }
                    .thenBy { it.fqName },
            )
            .map { symbol ->
                RankedNamedSymbolCandidate(
                    symbol = symbol,
                    score = 100,
                    reasons = listOf("exact identity and constraints match"),
                )
            }
            .toList()
        return if (containingType == null) {
            rankedCandidates
                .take(EXACT_CARDINALITY_LIMIT)
                .map { candidate -> ExactNamedSymbolCandidate(candidate, resolvedConstraintSymbol = null) }
        } else {
            rankedCandidates
                .mapNotNull { candidate ->
                    val resolved = resolveNamedSymbol(
                        candidate = candidate,
                        includeDeclarationScope = false,
                        includeDocumentation = false,
                    ) ?: return@mapNotNull null
                    if (exactContainingTypeMatches(containingType, resolved.symbol)) {
                        ExactNamedSymbolCandidate(candidate, resolved.symbol)
                    } else {
                        null
                    }
                }
                .take(EXACT_CARDINALITY_LIMIT)
        }
    }

    private fun exactIdentityMatches(requested: String, candidateFqName: String): Boolean {
        val normalizedRequested = normalizedKotlinIdentity(requested)
        val normalizedCandidate = normalizedKotlinIdentity(candidateFqName)
        return if (normalizedRequested.contains('.')) {
            normalizedCandidate == normalizedRequested
        } else {
            normalizedCandidate.substringAfterLast('.') == normalizedRequested
        }
    }

    private fun normalizedKotlinIdentity(value: String): String = value
        .split('.')
        .joinToString(".") { segment ->
            segment.removeSurrounding("`")
        }

    private fun exactWorkspaceSymbolPattern(value: String): String =
        "^${Regex.escape(normalizedKotlinIdentity(value).substringAfterLast('.'))}$"

    private fun exactFileHintMatches(fileHint: String, candidateFile: String): Boolean {
        val normalizedHint = Path.of(fileHint).normalize()
        val normalizedCandidate = Path.of(candidateFile).normalize()
        return if (normalizedHint.isAbsolute) {
            normalizedCandidate == normalizedHint
        } else {
            normalizedCandidate.endsWith(normalizedHint)
        }
    }

    private fun exactContainingTypeMatches(containingType: String, candidate: Symbol): Boolean {
        val candidateContainer = candidate.containingDeclaration ?: return false
        val normalizedRequested = normalizedKotlinIdentity(containingType)
        val normalizedCandidate = normalizedKotlinIdentity(candidateContainer)
        return if (normalizedRequested.contains('.')) {
            normalizedCandidate == normalizedRequested
        } else {
            normalizedCandidate.substringAfterLast('.') == normalizedRequested
        }
    }

    private fun symbolSearchPatterns(symbolName: String): List<String> =
        listOf(
            symbolName,
            symbolName.substringAfterLast('.'),
            normalizedKotlinIdentity(symbolName),
            normalizedKotlinIdentity(symbolName).substringAfterLast('.'),
        )
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun rankCandidate(
        candidate: Symbol,
        requestedSymbol: String,
        fileHint: String?,
        kind: WrapperNamedSymbolKind?,
        containingType: String?,
        line: Int?,
        codeSnippet: String?,
    ): RankedNamedSymbolCandidate {
        var score = 20
        val reasons = mutableListOf<String>()
        val simpleName = candidate.fqName.substringAfterLast('.')
        val requestedSimpleName = requestedSymbol.substringAfterLast('.')
        if (candidate.fqName == requestedSymbol) {
            score += 50
            reasons += "exact fully-qualified match"
        } else if (simpleName == requestedSimpleName) {
            score += 35
            reasons += "exact simple-name match"
        } else if (simpleName.contains(requestedSimpleName, ignoreCase = true)) {
            score += 10
            reasons += "simple name contains query"
        }

        if (kind != null && candidate.kind == kind.toSymbolKind()) {
            score += 15
            reasons += "kind matches ${kind.name.lowercase()}"
        } else if (kind != null) {
            score -= 30
        }

        if (!containingType.isNullOrBlank() && candidate.containingDeclaration?.endsWith(containingType) == true) {
            score += 15
            reasons += "containing declaration matches hint"
        }

        val fileHintMatches = !fileHint.isNullOrBlank() && candidate.location.filePath.endsWith(fileHint.removePrefix("/"))
        if (fileHintMatches) {
            score += 15
            reasons += "file matches hint"
        }

        if (line != null && (fileHint.isNullOrBlank() || fileHintMatches)) {
            val distance = kotlin.math.abs(candidate.location.startLine - line)
            val lineScore = when {
                distance == 0 -> 10
                distance <= 2 -> 7
                distance <= 5 -> 4
                else -> 0
            }
            if (lineScore > 0) {
                score += lineScore
                reasons += "line is $distance away"
            }
        }

        val snippetOverlap = snippetOverlap(codeSnippet, candidate)
        if (snippetOverlap > 0) {
            val snippetScore = minOf(10, snippetOverlap * 2)
            score += snippetScore
            reasons += "snippet overlaps $snippetOverlap token(s)"
        }

        return RankedNamedSymbolCandidate(
            symbol = candidate,
            score = score.coerceAtMost(100),
            reasons = reasons.ifEmpty { listOf("matched workspace symbol search") },
        )
    }

    private fun snippetOverlap(codeSnippet: String?, candidate: Symbol): Int {
        val queryTokens = codeSnippet?.tokens().orEmpty()
        if (queryTokens.isEmpty()) return 0
        val candidateTokens = listOf(
            candidate.fqName,
            candidate.location.preview,
            candidate.location.filePath,
            candidate.containingDeclaration.orEmpty(),
        ).joinToString(" ").tokens()
        return queryTokens.intersect(candidateTokens).size
    }

    private fun RankedNamedSymbolCandidate.toDiscoveryCandidate(
        rank: Int,
        workspaceRoot: String,
        requestedSymbol: String,
    ): KastDiscoveryCandidate {
        val params = KastResolveParams(
            workspaceRoot = workspaceRoot,
            symbol = requestedSymbol,
            fileHint = symbol.location.filePath,
            kind = symbol.kind.toWrapperNamedSymbolKindOrNull(),
            containingType = symbol.containingDeclaration,
        )
        return KastDiscoveryCandidate(
            rank = rank,
            confidence = score / 100.0,
            symbol = symbol,
            reasons = reasons,
            resolveParams = params,
            nextRequest = KastNextRequest(
                method = "symbol/resolve",
                params = params,
            ),
        )
    }

    private suspend fun resolveContext(
        symbol: Symbol,
        request: KastResolveRequest,
    ): KastResolveContext? {
        val surroundingText = request.surroundingLines?.let { lines ->
            sourceTextWindow(symbol, lines)
        }
        val surroundingMembers = if (request.includeSurroundingMembers) {
            surroundingMembers(symbol)
        } else {
            null
        }
        return if (surroundingText == null && surroundingMembers == null) {
            null
        } else {
            KastResolveContext(
                surroundingText = surroundingText,
                surroundingMembers = surroundingMembers,
            )
        }
    }

    private fun sourceTextWindow(symbol: Symbol, surroundingLines: Int): KastSourceTextWindow? {
        val path = Path.of(symbol.location.filePath)
        if (!Files.exists(path)) return null
        val lines = Files.readString(path).lines()
        if (lines.isEmpty()) return null
        val declarationStartLine = symbol.declarationScope?.startLine ?: symbol.location.startLine
        val declarationEndLine = symbol.declarationScope?.endLine ?: symbol.location.startLine
        val startLine = (declarationStartLine - surroundingLines).coerceAtLeast(1)
        val endLine = (declarationEndLine + surroundingLines).coerceAtMost(lines.size)
        return KastSourceTextWindow(
            filePath = symbol.location.filePath,
            startLine = startLine,
            endLine = endLine,
            text = lines.subList(startLine - 1, endLine).joinToString("\n"),
        )
    }

    private suspend fun surroundingMembers(symbol: Symbol): List<Symbol> {
        requireReadCapability(ReadCapability.FILE_OUTLINE)
        val outline = backend.fileOutline(FileOutlineQuery(filePath = symbol.location.filePath).parsed())
        return outline.symbols
            .flatMap(OutlineSymbol::flatten)
            .filter { candidate ->
                candidate.location.filePath == symbol.location.filePath &&
                    candidate.fqName != symbol.fqName &&
                    candidate.containingDeclaration == symbol.containingDeclaration
            }
            .map(Symbol::withoutHeavyContext)
            .sortedWith(compareBy({ it.location.startLine }, { it.fqName }))
    }

    private fun validateResolveQuery(query: KastResolveQuery) {
        if (query.symbol.isBlank()) {
            throw ValidationException("symbol must not be blank")
        }
        val surroundingLines = query.surroundingLines ?: return
        if (surroundingLines < 0 || surroundingLines > MAX_SURROUNDING_LINES) {
            throw ValidationException("surroundingLines must be between 0 and $MAX_SURROUNDING_LINES")
        }
    }

    private fun validateDiscoverQuery(query: KastDiscoverQuery) {
        if (query.symbol.isBlank()) {
            throw ValidationException("symbol must not be blank")
        }
        if (query.maxResults <= 0) {
            throw ValidationException("maxResults must be greater than 0")
        }
        if (query.maxResults > config.maxResults) {
            throw ValidationException("maxResults must be less than or equal to server maxResults (${config.maxResults})")
        }
        val line = query.line
        if (line != null && line <= 0) {
            throw ValidationException("line must be greater than 0")
        }
    }

    private fun validateReferencesQuery(query: KastReferencesQuery) {
        if (query.selector.fqName.isBlank()) {
            throw ValidationException("selector.fqName must not be blank")
        }
        if (query.selector.declarationFile.isBlank()) {
            throw ValidationException("selector.declarationFile must not be blank")
        }
        if (query.selector.declarationStartOffset < 0) {
            throw ValidationException("selector.declarationStartOffset must not be negative")
        }
        if (query.maxResults <= 0) {
            throw ValidationException("maxResults must be greater than 0")
        }
        if (query.maxResults > config.maxResults) {
            throw ValidationException("maxResults must be less than or equal to server maxResults (${config.maxResults})")
        }
    }

    private suspend fun resolveRelationshipSubject(
        selector: KastExactSymbolSelector,
    ): SymbolIdentity? {
        requireReadCapability(ReadCapability.RESOLVE_SYMBOL)
        return try {
            backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(
                        filePath = selector.declarationFile,
                        offset = selector.declarationStartOffset,
                    ),
                ).parsed(),
            ).symbol.toSymbolIdentity()
        } catch (_: NotFoundException) {
            null
        }
    }

    private suspend fun SelectorSelection.Selected.resolveSubject(): SymbolIdentity? = when (this) {
        is SelectorSelection.Explicit -> resolveRelationshipSubject(selector)
        is SelectorSelection.Handle -> selector.toHandleSubject()
    }

    private fun validateRelationshipQuery(
        selector: KastExactSymbolSelector,
        depth: Int?,
        maxResults: Int,
    ) {
        if (selector.fqName.isBlank()) {
            throw ValidationException("selector.fqName must not be blank")
        }
        if (selector.declarationFile.isBlank()) {
            throw ValidationException("selector.declarationFile must not be blank")
        }
        if (selector.declarationStartOffset < 0) {
            throw ValidationException("selector.declarationStartOffset must not be negative")
        }
        if (depth != null && depth !in 1..8) {
            throw ValidationException("depth must be from 1 through 8")
        }
        if (maxResults !in 1..minOf(200, config.maxResults)) {
            throw ValidationException(
                "maxResults must be from 1 through ${minOf(200, config.maxResults)}",
            )
        }
    }

    private fun limitedRelationshipEvidence(
        knownMinimumCount: Int,
        first: RelationshipSearchLimitation,
        vararg additional: RelationshipSearchLimitation,
    ): RelationshipResultEvidence.Limited = RelationshipResultEvidence.Limited(
        cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
        coverage = RelationshipSearchCoverage.limited(first, *additional),
    )

    private fun callContinuationOutcome(
        selector: KastExactSymbolSelector,
        subject: SymbolIdentity,
        failure: ConflictException,
    ): KastCallersResponse = when (failure.details["continuationFailure"]) {
        "generationChanged" -> KastCallersCursorStaleResponse(
            selector = selector,
            reason = RelationCursorStaleReason.GENERATION_CHANGED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.GENERATION_CHANGED,
            ),
        )
        "expired" -> KastCallersCursorStaleResponse(
            selector = selector,
            reason = RelationCursorStaleReason.EXPIRED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_EXPIRED,
            ),
        )
        "familyMismatch" -> KastCallersCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.FAMILY_MISMATCH,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "queryMismatch" -> KastCallersCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.QUERY_MISMATCH,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "unknown" -> KastCallersCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.UNKNOWN_HANDLE,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "candidateBudgetReached" -> KastCallersDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastCallDegradedReason.CANDIDATE_BUDGET_REACHED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CANDIDATE_BUDGET_REACHED,
            ),
        )
        "traversalStateBudgetReached" -> KastCallersDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastCallDegradedReason.TRAVERSAL_STATE_BUDGET_REACHED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.TRAVERSAL_STATE_BUDGET_REACHED,
            ),
        )
        "timeout" -> KastCallersDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastCallDegradedReason.TIMEOUT,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.TIMED_OUT,
            ),
        )
        else -> throw failure
    }

    private fun implementationContinuationOutcome(
        selector: KastExactSymbolSelector,
        subject: SymbolIdentity,
        failure: ConflictException,
    ): KastImplementationsResponse = when (failure.details["continuationFailure"]) {
        "generationChanged" -> KastImplementationsCursorStaleResponse(
            selector = selector,
            reason = RelationCursorStaleReason.GENERATION_CHANGED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.GENERATION_CHANGED,
            ),
        )
        "expired" -> KastImplementationsCursorStaleResponse(
            selector = selector,
            reason = RelationCursorStaleReason.EXPIRED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_EXPIRED,
            ),
        )
        "familyMismatch" -> KastImplementationsCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.FAMILY_MISMATCH,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "queryMismatch" -> KastImplementationsCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.QUERY_MISMATCH,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "unknown" -> KastImplementationsCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.UNKNOWN_HANDLE,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "candidateBudgetReached" -> KastImplementationsDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastImplementationsDegradedReason.CANDIDATE_BUDGET_REACHED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CANDIDATE_BUDGET_REACHED,
            ),
        )
        "traversalStateBudgetReached" -> KastImplementationsDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastImplementationsDegradedReason.TRAVERSAL_STATE_BUDGET_REACHED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.TRAVERSAL_STATE_BUDGET_REACHED,
            ),
        )
        "timeout" -> KastImplementationsDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastImplementationsDegradedReason.TIMEOUT,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.TIMED_OUT,
            ),
        )
        else -> throw failure
    }

    private fun hierarchyContinuationOutcome(
        selector: KastExactSymbolSelector,
        subject: SymbolIdentity,
        failure: ConflictException,
    ): KastHierarchyResponse = when (failure.details["continuationFailure"]) {
        "generationChanged" -> KastHierarchyCursorStaleResponse(
            selector = selector,
            reason = RelationCursorStaleReason.GENERATION_CHANGED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.GENERATION_CHANGED,
            ),
        )
        "expired" -> KastHierarchyCursorStaleResponse(
            selector = selector,
            reason = RelationCursorStaleReason.EXPIRED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_EXPIRED,
            ),
        )
        "familyMismatch" -> KastHierarchyCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.FAMILY_MISMATCH,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "queryMismatch" -> KastHierarchyCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.QUERY_MISMATCH,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "unknown" -> KastHierarchyCursorInvalidResponse(
            selector = selector,
            reason = RelationCursorInvalidReason.UNKNOWN_HANDLE,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
        )
        "candidateBudgetReached" -> KastHierarchyDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastHierarchyDegradedReason.CANDIDATE_BUDGET_REACHED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.CANDIDATE_BUDGET_REACHED,
            ),
        )
        "traversalStateBudgetReached" -> KastHierarchyDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastHierarchyDegradedReason.TRAVERSAL_STATE_BUDGET_REACHED,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.TRAVERSAL_STATE_BUDGET_REACHED,
            ),
        )
        "timeout" -> KastHierarchyDegradedResponse(
            selector = selector,
            subject = subject,
            reason = KastHierarchyDegradedReason.TIMEOUT,
            evidence = limitedRelationshipEvidence(
                0,
                RelationshipSearchLimitation.TIMED_OUT,
            ),
        )
        else -> throw failure
    }

    private fun KastExactSymbolSelector.normalizedFor(
        workspaceRoot: String,
    ): KastExactSymbolSelector {
        val input = Path.of(declarationFile)
        val normalized = if (input.isAbsolute) {
            input.toAbsolutePath().normalize()
        } else {
            Path.of(workspaceRoot).resolve(input).toAbsolutePath().normalize()
        }
        return copy(declarationFile = normalized.toString())
    }

    private fun Symbol.toSymbolIdentity(): SymbolIdentity = SymbolIdentity(
        fqName = fqName,
        kind = kind,
        declarationFile = NormalizedPath.parse(location.filePath),
        declarationStartOffset = io.github.amichne.kast.api.contract.NonNegativeInt(location.startOffset),
        containingType = containingDeclaration,
    )

    private fun Symbol.toExactSelector(): KastExactSymbolSelector = KastExactSymbolSelector(
        fqName = fqName,
        declarationFile = location.filePath,
        declarationStartOffset = location.startOffset,
        kind = kind,
        containingType = containingDeclaration,
    )

    private fun issueSelectorHandle(symbol: Symbol): String =
        when (
            val issued = backend.selectorHandles.issue(
                selector = symbol.toExactSelector(),
                allowedFamilies = symbol.kind.selectorOperationFamilies(),
            )
        ) {
            is SelectorHandleAuthority.IssueResult.Issued -> issued.handle.value
            SelectorHandleAuthority.IssueResult.Unavailable -> throw CapabilityNotSupportedException(
                capability = "SELECTOR_HANDLES",
                message = "The semantic backend cannot issue reusable selector handles",
            )
        }

    private fun selectSelector(
        explicitSelector: KastExactSymbolSelector?,
        selectorHandle: String?,
        workspaceRoot: String,
        family: SelectorOperationFamily,
    ): SelectorSelection {
        return when {
            explicitSelector != null && selectorHandle == null ->
                SelectorSelection.Explicit(explicitSelector.normalizedFor(workspaceRoot))
            explicitSelector == null && selectorHandle != null -> {
                when (
                    val resolution = backend.selectorHandles.resolve(
                        handle = selectorHandle,
                        workspaceRoot = workspaceRoot,
                        family = family,
                    )
                ) {
                    is SelectorHandleAuthority.Resolution.Resolved ->
                        SelectorSelection.Handle(resolution.selector.normalizedFor(workspaceRoot))
                    is SelectorHandleAuthority.Resolution.Rejected ->
                        SelectorSelection.Rejected(resolution.reason)
                }
            }
            else -> throw ValidationException(
                "Provide exactly one of selector or selectorHandle",
            )
        }
    }

    private fun KastExactSymbolSelector.toHandleSubject(): SymbolIdentity = SymbolIdentity(
        fqName = fqName,
        kind = kind ?: throw ValidationException("Backend-issued selector handle omitted kind"),
        declarationFile = NormalizedPath.parse(declarationFile),
        declarationStartOffset = NonNegativeInt(declarationStartOffset),
        containingType = containingType,
    )

    private fun SymbolKind.selectorOperationFamilies(): Set<SelectorOperationFamily> = when (this) {
        SymbolKind.CLASS, SymbolKind.INTERFACE -> setOf(
            SelectorOperationFamily.REFERENCES,
            SelectorOperationFamily.IMPLEMENTATIONS,
            SelectorOperationFamily.HIERARCHY,
            SelectorOperationFamily.IMPACT,
            SelectorOperationFamily.RENAME,
            SelectorOperationFamily.REPLACE_DECLARATION,
        )
        SymbolKind.OBJECT -> setOf(
            SelectorOperationFamily.REFERENCES,
            SelectorOperationFamily.HIERARCHY,
            SelectorOperationFamily.IMPACT,
            SelectorOperationFamily.RENAME,
            SelectorOperationFamily.REPLACE_DECLARATION,
        )
        SymbolKind.FUNCTION -> setOf(
            SelectorOperationFamily.REFERENCES,
            SelectorOperationFamily.CALLERS,
            SelectorOperationFamily.CALLEES,
            SelectorOperationFamily.IMPACT,
            SelectorOperationFamily.RENAME,
            SelectorOperationFamily.REPLACE_DECLARATION,
        )
        SymbolKind.PROPERTY -> setOf(
            SelectorOperationFamily.REFERENCES,
            SelectorOperationFamily.IMPACT,
            SelectorOperationFamily.RENAME,
            SelectorOperationFamily.REPLACE_DECLARATION,
        )
        SymbolKind.PARAMETER -> setOf(
            SelectorOperationFamily.REFERENCES,
            SelectorOperationFamily.RENAME,
        )
        SymbolKind.UNKNOWN -> emptySet()
    }

    private fun KastExactSymbolSelector.matches(actual: SymbolIdentity): Boolean =
        fqName == actual.fqName &&
            NormalizedPath.parse(declarationFile) == actual.declarationFile &&
            declarationStartOffset == actual.declarationStartOffset.value &&
            (kind == null || kind == actual.kind) &&
            (containingType == null || containingType == actual.containingType)

    private suspend fun workspaceRootFor(explicit: String?): String =
        explicit?.takeIf(String::isNotBlank)?.normalizedAbsolutePath() ?: backend.runtimeStatus().workspaceRoot

    private suspend fun requireReadCapability(capability: ReadCapability) {
        requireCapabilities(readCapabilities = setOf(capability))
    }

    private suspend fun requireMutationCapability(capability: MutationCapability) {
        requireCapabilities(mutationCapabilities = setOf(capability))
    }

    private suspend fun requireCapabilities(
        readCapabilities: Set<ReadCapability> = emptySet(),
        mutationCapabilities: Set<MutationCapability> = emptySet(),
    ) {
        val capabilities = backend.capabilities()
        val missingReadCapability = readCapabilities.firstOrNull { capability ->
            capability !in capabilities.readCapabilities
        }
        if (missingReadCapability != null) {
            throw CapabilityNotSupportedException(
                capability = missingReadCapability.name,
                message = "The backend does not advertise $missingReadCapability",
            )
        }
        val missingMutationCapability = mutationCapabilities.firstOrNull { capability ->
            capability !in capabilities.mutationCapabilities
        }
        if (missingMutationCapability != null) {
            throw CapabilityNotSupportedException(
                capability = missingMutationCapability.name,
                message = "The backend does not advertise $missingMutationCapability",
            )
        }
    }

    private fun resolveContent(content: String?, contentFile: String?): String {
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

    private data class ResolvedNamedSymbol(
        val symbol: Symbol,
        val filePath: String,
        val offset: Int,
        val candidateCount: Int,
        val alternativeFqNames: List<String>,
    )

    private data class RankedNamedSymbolCandidate(
        val symbol: Symbol,
        val score: Int,
        val reasons: List<String>,
    )

}

private fun MutationProgressReporter.enter(stage: KastMutationProgressStage) {
    report(MutationProgressEvent.StageEntered(stage))
}

private fun MutationProgressReporter.editApplicationCompleted() {
    report(MutationProgressEvent.EditApplicationCompleted)
}

private fun String.normalizedAbsolutePath(): String = Path.of(this).toAbsolutePath().normalize().toString()

private fun String.readTextIfPresent(): String? {
    val path = Path.of(this)
    return if (Files.exists(path)) Files.readString(path) else null
}

private fun WrapperNamedSymbolKind.toSymbolKind(): SymbolKind = when (this) {
    WrapperNamedSymbolKind.CLASS -> SymbolKind.CLASS
    WrapperNamedSymbolKind.INTERFACE -> SymbolKind.INTERFACE
    WrapperNamedSymbolKind.OBJECT -> SymbolKind.OBJECT
    WrapperNamedSymbolKind.FUNCTION -> SymbolKind.FUNCTION
    WrapperNamedSymbolKind.PROPERTY -> SymbolKind.PROPERTY
}

private fun SymbolKind.toWrapperNamedSymbolKindOrNull(): WrapperNamedSymbolKind? = when (this) {
    SymbolKind.CLASS -> WrapperNamedSymbolKind.CLASS
    SymbolKind.INTERFACE -> WrapperNamedSymbolKind.INTERFACE
    SymbolKind.OBJECT -> WrapperNamedSymbolKind.OBJECT
    SymbolKind.FUNCTION -> WrapperNamedSymbolKind.FUNCTION
    SymbolKind.PROPERTY -> WrapperNamedSymbolKind.PROPERTY
    else -> null
}

private fun OutlineSymbol.flatten(): List<Symbol> =
    listOf(symbol) + children.flatMap(OutlineSymbol::flatten)

private fun Symbol.withoutHeavyContext(): Symbol = copy(
    declarationScope = null,
)

private fun String.tokens(): Set<String> =
    lowercase()
        .split(Regex("[^a-z0-9_]+"))
        .filter { it.length >= 2 }
        .toSet()

private fun WrapperCallDirection.toCallDirection(): CallDirection = when (this) {
    WrapperCallDirection.INCOMING -> CallDirection.INCOMING
    WrapperCallDirection.OUTGOING -> CallDirection.OUTGOING
}

private fun WrapperScaffoldMode.toInsertionTarget(): SemanticInsertionTarget = when (this) {
    WrapperScaffoldMode.IMPLEMENT -> SemanticInsertionTarget.CLASS_BODY_END
    WrapperScaffoldMode.REPLACE -> SemanticInsertionTarget.CLASS_BODY_START
    WrapperScaffoldMode.CONSOLIDATE -> SemanticInsertionTarget.FILE_BOTTOM
    WrapperScaffoldMode.EXTRACT -> SemanticInsertionTarget.AFTER_IMPORTS
}

private fun placeholderLogFile(): String = "/dev/null"

private fun workspaceSymbolPageToken(limit: Int): String = limit.toString()

@Suppress("UNCHECKED_CAST")
private fun <T, R : PageableResult<T>> R.withLimit(
    limit: Int,
    nextPageToken: (T) -> String,
): R {
    if (items.size <= limit) {
        return this
    }
    return withItems(
        items = items.take(limit),
        page = PageInfo(
            truncated = true,
            nextPageToken = nextPageToken(items[limit - 1]),
        ),
    ) as R
}
