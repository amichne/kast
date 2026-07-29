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

internal suspend fun SkillRpcContext.references(request: KastReferencesRequest): KastReferencesResponse {
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
            references = completeResult.references,
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

internal suspend fun SkillRpcContext.callers(request: KastCallersRequest): KastCallersResponse {
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
    val subject = resolveSubject(selected)
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
            records = result.records,
        )
    }
}

internal suspend fun SkillRpcContext.implementations(
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
    val subject = resolveSubject(selected)
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
            records = result.records,
        )
    }
}
