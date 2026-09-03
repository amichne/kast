package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceCoordinateUnitDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationSemanticIdentityDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceEntityTargetDocument
import io.github.amichne.kast.protocol.contract.SourceLengthDocument
import io.github.amichne.kast.protocol.contract.SourceNestingDepthDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionDocument
import io.github.amichne.kast.protocol.contract.SourceRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionRangeDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceTextWithheldReasonDocument
import io.github.amichne.kast.protocol.contract.SourceUnresolvedReasonDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.source.contract.BodyKind
import io.github.amichne.kast.source.contract.CompilerUnresolvedReason
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EnclosingRegionKind
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.LineCount
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceEntityTarget
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSelectorToken
import io.github.amichne.kast.source.contract.SourceSelectorTokenCodec
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.SourceTextWithheldReason
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.protocol.contract.SourceReadResult as ProtocolSourceReadResult
import io.github.amichne.kast.source.contract.SourceReadAnchor as DomainSourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadQualification as DomainSourceReadQualification
import io.github.amichne.kast.source.contract.SourceReadRejection as DomainSourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest as DomainSourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult as DomainSourceReadResult

/** Canonical protocol/domain adapter for the sole authoritative bounded source read. */
internal class CanonicalSourceReadHandler(
    private val operations: SourceReadOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<
    SourceReadRequest,
    ProtocolSourceReadResult,
    SourceReadQualification,
    SourceReadRejection,
    > {
    override suspend fun execute(
        request: SourceReadRequest,
    ): OperationOutcome<ProtocolSourceReadResult, SourceReadQualification, SourceReadRejection> {
        val domainRequest = when (val admitted = request.admit(authority)) {
            is SourceRequestAdmission.Admitted -> admitted.request
            is SourceRequestAdmission.Rejected ->
                return OperationOutcome.Rejected(admitted.reason)
        }
        return when (val result = operations.read(domainRequest)) {
            is DomainSourceReadResult.Rejected ->
                OperationOutcome.Rejected(result.reason.protocol())
            is DomainSourceReadResult.Complete -> when (val projected = result.project(authority)) {
                is SourceResultProjection.Projected -> OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.SOURCE_READ.id,
                        result.snapshot.lease.generation,
                        projected.result,
                    ),
                )
                SourceResultProjection.Rejected -> contractViolation()
            }
            is DomainSourceReadResult.Qualified -> when (val projected = result.project(authority)) {
                is SourceQualifiedResultProjection.Projected -> OperationOutcome.Qualified(
                    EvidenceEnvelope(
                        CanonicalOperation.SOURCE_READ.id,
                        result.snapshot.lease.generation,
                        projected.result,
                    ),
                    projected.qualification,
                )
                SourceQualifiedResultProjection.Rejected -> contractViolation()
            }
        }
    }
}

private sealed interface SourceRequestAdmission {
    data class Admitted(val request: DomainSourceReadRequest) : SourceRequestAdmission
    data class Rejected(val reason: SourceReadRejection) : SourceRequestAdmission
}

private fun SourceReadRequest.admit(authority: CanonicalProtocolAuthority): SourceRequestAdmission {
    val domainAnchor = when (val requested = anchor) {
        is SourceReadAnchorDocument.Candidate -> when (val lookup = authority.candidate(requested.selector)) {
            is CandidateSelectorLookup.Found ->
                DomainSourceReadAnchor.Candidate(lookup.selector)
            CandidateSelectorLookup.Missing ->
                return SourceRequestAdmission.Rejected(SourceReadRejection.CANDIDATE_STALE)
        }
        is SourceReadAnchorDocument.Symbol -> when (val lookup = authority.exact(requested.selector)) {
            is ExactSelectorLookup.Found -> DomainSourceReadAnchor.Symbol(lookup.selector)
            ExactSelectorLookup.Missing ->
                return SourceRequestAdmission.Rejected(SourceReadRejection.STALE_GENERATION)
        }
        is SourceReadAnchorDocument.Source -> {
            val token = SourceSelectorToken.parse(requested.selector.value).refinedOrNull()
                ?: return SourceRequestAdmission.Rejected(SourceReadRejection.SOURCE_SELECTOR_STALE)
            val selector = SourceSelectorTokenCodec.decode(token).refinedOrNull()
                ?: return SourceRequestAdmission.Rejected(SourceReadRejection.SOURCE_SELECTOR_STALE)
            DomainSourceReadAnchor.Source(selector)
        }
    }
    val domainRegion = when (val requested = region) {
        SourceRegionSelectionDocument.Anchor -> RegionSelection.Anchor
        is SourceRegionSelectionDocument.Body -> RegionSelection.Body(
            when (requested.kind) {
                SourceBodyKindDocument.CALLABLE -> BodyKind.CALLABLE
                SourceBodyKindDocument.CLASS -> BodyKind.CLASS
            },
        )
        SourceRegionSelectionDocument.File -> RegionSelection.File
        is SourceRegionSelectionDocument.Enclosing -> RegionSelection.Enclosing(
            when (requested.kind) {
                SourceEnclosingRegionKindDocument.DECLARATION -> EnclosingRegionKind.DECLARATION
                SourceEnclosingRegionKindDocument.CALLABLE_BODY -> EnclosingRegionKind.CALLABLE_BODY
                SourceEnclosingRegionKindDocument.CLASS_BODY -> EnclosingRegionKind.CLASS_BODY
            },
        )
    }
    val domainEntities = entities.domain()
        ?: return SourceRequestAdmission.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
    val domainText = when (val requested = text) {
        SourceTextRequestDocument.Complete -> TextProjection.Complete
        SourceTextRequestDocument.None -> TextProjection.None
        is SourceTextRequestDocument.Window -> {
            val before = LineCount.parse(requested.beforeLines.value).refinedOrNull()
                ?: return SourceRequestAdmission.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            val after = LineCount.parse(requested.afterLines.value).refinedOrNull()
                ?: return SourceRequestAdmission.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            TextProjection.window(before, after)
        }
    }
    val domainEntityLimit = SourceEntityLimit.parse(entityLimit.value).refinedOrNull()
        ?: return SourceRequestAdmission.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
    val domainTextByteLimit = SourceTextByteLimit.parse(textByteLimit.value).refinedOrNull()
        ?: return SourceRequestAdmission.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
    val domainPage = when (val requested = page) {
        SourceReadPageDocument.First -> SourceReadPage.First
        is SourceReadPageDocument.Continue -> SourceReadPage.Continue(
            SourceReadContinuation.parse(requested.continuation.value).refinedOrNull()
                ?: return SourceRequestAdmission.Rejected(SourceReadRejection.CONTRACT_VIOLATION),
        )
    }
    return SourceRequestAdmission.Admitted(
        DomainSourceReadRequest(
            domainAnchor,
            domainRegion,
            domainEntities,
            domainText,
            domainEntityLimit,
            domainTextByteLimit,
            domainPage,
        ),
    )
}

private fun SourceEntitySelectionDocument.domain(): EntitySelection? = when (this) {
    SourceEntitySelectionDocument.None -> EntitySelection.None
    is SourceEntitySelectionDocument.Matching -> {
        val mapped = filters.map { it.domain() ?: return null }
        EntitySelection.matching(
            when (containment) {
                SourceContainmentDocument.DIRECT -> Containment.DIRECT
                SourceContainmentDocument.DESCENDANTS -> Containment.DESCENDANTS
            },
            mapped,
        ).refinedOrNull()
    }
}

private fun SourceEntityFilterDocument.domain(): EntityFilter? = when (this) {
    is SourceEntityFilterDocument.Declarations -> {
        if (kinds != kinds.distinct().sortedBy { it.ordinal }) return null
        val domainKinds = DeclarationKindSelection.from(kinds.map { it.domain() }.toSet())
            .refinedOrNull() ?: return null
        val domainVisibility = when (val requested = visibility) {
            SourceVisibilitySelectionDocument.Any -> VisibilitySelection.Any
            is SourceVisibilitySelectionDocument.Exact -> {
                if (requested.values != requested.values.distinct().sortedBy { it.ordinal }) {
                    return null
                }
                VisibilitySelection.exact(requested.values.map { it.domain() }.toSet())
                    .refinedOrNull() ?: return null
            }
        }
        EntityFilter.Declarations(domainKinds, domainVisibility)
    }
    SourceEntityFilterDocument.Parameters -> EntityFilter.Parameters
    SourceEntityFilterDocument.Calls -> EntityFilter.Calls
    SourceEntityFilterDocument.References -> EntityFilter.References
}

private fun SourceDeclarationKindDocument.domain(): DeclarationKind = when (this) {
    SourceDeclarationKindDocument.CLASSLIKE -> DeclarationKind.CLASSLIKE
    SourceDeclarationKindDocument.CONSTRUCTOR -> DeclarationKind.CONSTRUCTOR
    SourceDeclarationKindDocument.FUNCTION -> DeclarationKind.FUNCTION
    SourceDeclarationKindDocument.PROPERTY -> DeclarationKind.PROPERTY
    SourceDeclarationKindDocument.TYPE_ALIAS -> DeclarationKind.TYPE_ALIAS
}

private fun SourceDeclarationVisibilityDocument.domain(): DeclarationVisibility = when (this) {
    SourceDeclarationVisibilityDocument.PUBLIC -> DeclarationVisibility.PUBLIC
    SourceDeclarationVisibilityDocument.PROTECTED -> DeclarationVisibility.PROTECTED
    SourceDeclarationVisibilityDocument.INTERNAL -> DeclarationVisibility.INTERNAL
    SourceDeclarationVisibilityDocument.PRIVATE -> DeclarationVisibility.PRIVATE
    SourceDeclarationVisibilityDocument.LOCAL -> DeclarationVisibility.LOCAL
}

private sealed interface SourceResultProjection {
    data class Projected(val result: ProtocolSourceReadResult) : SourceResultProjection
    data object Rejected : SourceResultProjection
}

private sealed interface SourceQualifiedResultProjection {
    data class Projected(
        val result: ProtocolSourceReadResult,
        val qualification: SourceReadQualification,
    ) : SourceQualifiedResultProjection
    data object Rejected : SourceQualifiedResultProjection
}

private fun DomainSourceReadResult.Complete.project(
    authority: CanonicalProtocolAuthority,
): SourceResultProjection = protocolResult(snapshot, region, entities, text, authority)
    ?.let(SourceResultProjection::Projected) ?: SourceResultProjection.Rejected

private fun DomainSourceReadResult.Qualified.project(
    authority: CanonicalProtocolAuthority,
): SourceQualifiedResultProjection {
    val result = protocolResult(snapshot, region, entities, text, authority)
        ?: return SourceQualifiedResultProjection.Rejected
    val protocolQualification = qualification.protocol()
        ?: return SourceQualifiedResultProjection.Rejected
    return SourceQualifiedResultProjection.Projected(result, protocolQualification)
}

private fun protocolResult(
    snapshot: io.github.amichne.kast.source.contract.SourceSnapshot,
    region: io.github.amichne.kast.source.contract.SourceRegion,
    entities: List<SourceEntity>,
    text: SourceTextProjection,
    authority: CanonicalProtocolAuthority,
): ProtocolSourceReadResult? {
    val snapshotDocument = SourceSnapshotDocument(
        canonicalRoot = protocolText(snapshot.lease.workspaceRoot.value) ?: return null,
        generation = snapshot.lease.generation.value,
        sourceState = protocolText(snapshot.sourceState.value) ?: return null,
        file = protocolText(snapshot.file.path.value) ?: return null,
        textIdentity = protocolText(snapshot.textIdentity.value) ?: return null,
        coordinateUnit = SourceCoordinateUnitDocument.UTF16_CODE_UNIT,
        length = SourceLengthDocument.parse(snapshot.length.value).refinedOrNull() ?: return null,
    )
    val regionDocument = SourceRegionDocument(
        kind = region.kind.protocol(),
        selection = region.selector.protocolSelection() ?: return null,
    )
    val entityDocuments = entities.map { it.protocol(authority) ?: return null }
    val boundedEntities = BoundedProtocolList.create(entityDocuments).refinedOrNull() ?: return null
    val textDocument = when (text) {
        SourceTextProjection.NotRequested -> SourceTextProjectionDocument.NotRequested
        is SourceTextProjection.Returned -> SourceTextProjectionDocument.Returned(
            text.selector.protocolSelection() ?: return null,
            ProtocolSourceText.parse(text.text).refinedOrNull() ?: return null,
        )
        is SourceTextProjection.Withheld -> SourceTextProjectionDocument.Withheld(
            when (text.reason) {
                SourceTextWithheldReason.BYTE_LIMIT_REACHED ->
                    SourceTextWithheldReasonDocument.BYTE_LIMIT_REACHED
                SourceTextWithheldReason.PROVIDER_UNAVAILABLE ->
                    SourceTextWithheldReasonDocument.PROVIDER_UNAVAILABLE
            },
        )
    }
    return ProtocolSourceReadResult(snapshotDocument, regionDocument, boundedEntities, textDocument)
}

private fun SourceEntity.protocol(authority: CanonicalProtocolAuthority): SourceEntityDocument? {
    val depth = SourceNestingDepthDocument.parse(nestingDepth.value).refinedOrNull() ?: return null
    val parent = protocolText(SourceSelectorTokenCodec.encode(parentSelector).value) ?: return null
    val selectionDocument = selector.protocolSelection() ?: return null
    return when (this) {
        is SourceEntity.Declaration -> SourceEntityDocument.Declaration(
            kind.protocol(),
            selector.presentName() ?: return null,
            visibility.protocol(),
            depth,
            parent,
            selectionDocument,
            semanticIdentity.protocol(authority) ?: return null,
        )
        is SourceEntity.ValueParameter -> SourceEntityDocument.ValueParameter(
            selector.presentName() ?: return null,
            depth,
            parent,
            selectionDocument,
        )
        is SourceEntity.Call -> SourceEntityDocument.Call(
            depth,
            parent,
            selectionDocument,
            calleeSelector.protocolSelection() ?: return null,
            target.protocol(authority) ?: return null,
        )
        is SourceEntity.Reference -> SourceEntityDocument.Reference(
            selector.presentName() ?: return null,
            depth,
            parent,
            selectionDocument,
            target.protocol(authority) ?: return null,
        )
    }
}

private fun SourceSelector.Entity.presentName(): ProtocolText? = when (val value = name) {
    SourceEntityName.Unavailable -> null
    is SourceEntityName.Present -> protocolText(value.value)
}

private fun DeclarationSemanticIdentity.protocol(
    authority: CanonicalProtocolAuthority,
): SourceDeclarationSemanticIdentityDocument? = when (this) {
    is DeclarationSemanticIdentity.Candidate -> when (
        val encoded = CanonicalSelectorCodec.encodeCandidate(selector)
    ) {
        is CanonicalSelectorEncoding.Encoded ->
            SourceDeclarationSemanticIdentityDocument.Candidate(encoded.token)
        is CanonicalSelectorEncoding.Rejected -> null
    }
    is DeclarationSemanticIdentity.ExistingSymbol -> when (val issued = authority.issueExact(selector)) {
        is ExactSelectorIssuance.Issued ->
            SourceDeclarationSemanticIdentityDocument.ExistingSymbol(issued.selector)
        is ExactSelectorIssuance.Rejected -> null
    }
}

private fun SourceEntityTarget.protocol(
    authority: CanonicalProtocolAuthority,
): SourceEntityTargetDocument? = when (this) {
    is SourceEntityTarget.Symbol -> when (val issued = authority.issueExact(selector)) {
        is ExactSelectorIssuance.Issued -> SourceEntityTargetDocument.Symbol(issued.selector)
        is ExactSelectorIssuance.Rejected -> null
    }
    is SourceEntityTarget.Local -> SourceEntityTargetDocument.Local(
        protocolText(SourceSelectorTokenCodec.encode(selector).value) ?: return null,
    )
    is SourceEntityTarget.Unresolved -> SourceEntityTargetDocument.Unresolved(reason.protocol())
}

private fun CompilerUnresolvedReason.protocol(): SourceUnresolvedReasonDocument = when (this) {
    CompilerUnresolvedReason.NAME_NOT_FOUND -> SourceUnresolvedReasonDocument.NAME_NOT_FOUND
    CompilerUnresolvedReason.AMBIGUOUS -> SourceUnresolvedReasonDocument.AMBIGUOUS
    CompilerUnresolvedReason.ERROR_TYPE -> SourceUnresolvedReasonDocument.ERROR_TYPE
    CompilerUnresolvedReason.UNSUPPORTED_TARGET -> SourceUnresolvedReasonDocument.UNSUPPORTED_TARGET
}

private fun SourceSelector.protocolSelection(): SourceSelectionDocument? {
    val start = ProtocolOffset.parse(range.startInclusive.value).refinedOrNull() ?: return null
    val end = ProtocolOffset.parse(range.endExclusive.value).refinedOrNull() ?: return null
    val protocolRange = SourceSelectionRangeDocument.create(start, end).refinedOrNull() ?: return null
    return SourceSelectionDocument(
        protocolText(SourceSelectorTokenCodec.encode(this).value) ?: return null,
        protocolRange,
    )
}

private fun DomainSourceReadQualification.protocol(): SourceReadQualification? {
    val count = SourceEntityCountDocument.parse(knownMinimumEntityCount.value).refinedOrNull()
        ?: return null
    val protocolLimitations = limitations.map { it.protocol() }
    val protocolContinuation = when (val value = continuation) {
        SourceReadContinuationState.Unavailable -> SourceReadContinuationStateDocument.Unavailable
        is SourceReadContinuationState.Available -> SourceReadContinuationStateDocument.Available(
            protocolText(value.continuation.value) ?: return null,
        )
    }
    return SourceReadQualification.create(count, protocolLimitations, protocolContinuation)
        .refinedOrNull()
}

private fun SourceReadLimitation.protocol(): SourceReadLimitationDocument = when (this) {
    SourceReadLimitation.ENTITY_LIMIT_REACHED -> SourceReadLimitationDocument.ENTITY_LIMIT_REACHED
    SourceReadLimitation.TEXT_BYTE_LIMIT_REACHED -> SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED
    SourceReadLimitation.WORK_LIMIT_REACHED -> SourceReadLimitationDocument.WORK_LIMIT_REACHED
    SourceReadLimitation.TIME_LIMIT_REACHED -> SourceReadLimitationDocument.TIME_LIMIT_REACHED
    SourceReadLimitation.DUMB_MODE_TRANSITION -> SourceReadLimitationDocument.DUMB_MODE_TRANSITION
    SourceReadLimitation.SEMANTIC_RESOLUTION_INCOMPLETE ->
        SourceReadLimitationDocument.SEMANTIC_RESOLUTION_INCOMPLETE
    SourceReadLimitation.UNSUPPORTED_ENTITY -> SourceReadLimitationDocument.UNSUPPORTED_ENTITY
    SourceReadLimitation.PROVIDER_FAILURE -> SourceReadLimitationDocument.PROVIDER_FAILURE
}

private fun io.github.amichne.kast.source.contract.SourceRegionKind.protocol(): SourceRegionKindDocument =
    when (this) {
        io.github.amichne.kast.source.contract.SourceRegionKind.ANCHOR -> SourceRegionKindDocument.ANCHOR
        io.github.amichne.kast.source.contract.SourceRegionKind.DECLARATION ->
            SourceRegionKindDocument.DECLARATION
        io.github.amichne.kast.source.contract.SourceRegionKind.CALLABLE_BODY ->
            SourceRegionKindDocument.CALLABLE_BODY
        io.github.amichne.kast.source.contract.SourceRegionKind.CLASS_BODY ->
            SourceRegionKindDocument.CLASS_BODY
        io.github.amichne.kast.source.contract.SourceRegionKind.FILE -> SourceRegionKindDocument.FILE
        io.github.amichne.kast.source.contract.SourceRegionKind.WINDOW -> SourceRegionKindDocument.WINDOW
    }

private fun DeclarationKind.protocol(): SourceDeclarationKindDocument = when (this) {
    DeclarationKind.CLASSLIKE -> SourceDeclarationKindDocument.CLASSLIKE
    DeclarationKind.CONSTRUCTOR -> SourceDeclarationKindDocument.CONSTRUCTOR
    DeclarationKind.FUNCTION -> SourceDeclarationKindDocument.FUNCTION
    DeclarationKind.PROPERTY -> SourceDeclarationKindDocument.PROPERTY
    DeclarationKind.TYPE_ALIAS -> SourceDeclarationKindDocument.TYPE_ALIAS
}

private fun DeclarationVisibility.protocol(): SourceDeclarationVisibilityDocument = when (this) {
    DeclarationVisibility.PUBLIC -> SourceDeclarationVisibilityDocument.PUBLIC
    DeclarationVisibility.PROTECTED -> SourceDeclarationVisibilityDocument.PROTECTED
    DeclarationVisibility.INTERNAL -> SourceDeclarationVisibilityDocument.INTERNAL
    DeclarationVisibility.PRIVATE -> SourceDeclarationVisibilityDocument.PRIVATE
    DeclarationVisibility.LOCAL -> SourceDeclarationVisibilityDocument.LOCAL
}

private fun DomainSourceReadRejection.protocol(): SourceReadRejection = when (this) {
    DomainSourceReadRejection.WORKSPACE_NOT_READY -> SourceReadRejection.WORKSPACE_NOT_READY
    DomainSourceReadRejection.WORKSPACE_ROOT_MISMATCH -> SourceReadRejection.WORKSPACE_ROOT_MISMATCH
    DomainSourceReadRejection.STALE_GENERATION -> SourceReadRejection.STALE_GENERATION
    DomainSourceReadRejection.SOURCE_STATE_MISMATCH -> SourceReadRejection.SOURCE_STATE_MISMATCH
    DomainSourceReadRejection.CANDIDATE_STALE -> SourceReadRejection.CANDIDATE_STALE
    DomainSourceReadRejection.SOURCE_SELECTOR_STALE -> SourceReadRejection.SOURCE_SELECTOR_STALE
    DomainSourceReadRejection.SOURCE_SNAPSHOT_MISMATCH -> SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH
    DomainSourceReadRejection.SOURCE_UNAVAILABLE -> SourceReadRejection.SOURCE_UNAVAILABLE
    DomainSourceReadRejection.DOCUMENT_DIRTY -> SourceReadRejection.DOCUMENT_DIRTY
    DomainSourceReadRejection.PSI_DOCUMENT_UNCOMMITTED -> SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED
    DomainSourceReadRejection.OUTSIDE_SOURCE_SCOPE -> SourceReadRejection.OUTSIDE_SOURCE_SCOPE
    DomainSourceReadRejection.ANCHOR_NOT_FOUND -> SourceReadRejection.ANCHOR_NOT_FOUND
    DomainSourceReadRejection.AMBIGUOUS_ANCHOR -> SourceReadRejection.AMBIGUOUS_ANCHOR
    DomainSourceReadRejection.REGION_NOT_APPLICABLE -> SourceReadRejection.REGION_NOT_APPLICABLE
    DomainSourceReadRejection.REGION_ABSENT -> SourceReadRejection.REGION_ABSENT
    DomainSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE ->
        SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE
    DomainSourceReadRejection.CONTRACT_VIOLATION -> SourceReadRejection.CONTRACT_VIOLATION
}

private fun protocolText(raw: String): ProtocolText? = ProtocolText.parse(raw).refinedOrNull()

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private fun contractViolation(): OperationOutcome.Rejected<SourceReadRejection> =
    OperationOutcome.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
