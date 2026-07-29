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

internal suspend fun SkillRpcContext.hierarchy(request: KastHierarchyRequest): KastHierarchyResponse {
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
    val subject = resolveSubject(selected)
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
            records = result.records,
        )
    }
}


internal suspend fun SkillRpcContext.resolveRelationshipSubject(
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

internal suspend fun SkillRpcContext.resolveSubject(selected: SelectorSelection.Selected): SymbolIdentity? = when (selected) {
    is SelectorSelection.Explicit -> resolveRelationshipSubject(selected.selector)
    is SelectorSelection.Handle -> selected.selector.toHandleSubject()
}

internal fun SkillRpcContext.validateRelationshipQuery(
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

internal fun SkillRpcContext.limitedRelationshipEvidence(
    knownMinimumCount: Int,
    first: RelationshipSearchLimitation,
    vararg additional: RelationshipSearchLimitation,
): RelationshipResultEvidence.Limited = RelationshipResultEvidence.Limited(
    cardinality = ResultCardinality.KnownMinimum(knownMinimumCount),
    coverage = RelationshipSearchCoverage.limited(first, *additional),
)


internal fun SkillRpcContext.callContinuationOutcome(
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

internal fun SkillRpcContext.implementationContinuationOutcome(
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

internal fun SkillRpcContext.hierarchyContinuationOutcome(
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
