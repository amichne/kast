package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceCoordinateUnitDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationSemanticIdentityDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityTargetDocument
import io.github.amichne.kast.protocol.contract.SourceInternalObligation
import io.github.amichne.kast.protocol.contract.SourceLengthDocument
import io.github.amichne.kast.protocol.contract.SourceNestingDepthDocument
import io.github.amichne.kast.protocol.contract.SourceReadCause
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult as ProtocolSourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionDocument
import io.github.amichne.kast.protocol.contract.SourceRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceSelectionRangeDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotContextDocument
import io.github.amichne.kast.protocol.contract.SourceSnapshotDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextWithheldReasonDocument
import io.github.amichne.kast.protocol.contract.SourceUnresolvedReasonDocument
import io.github.amichne.kast.source.contract.CompilerUnresolvedReason
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceEntityTarget
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadQualification as DomainSourceReadQualification
import io.github.amichne.kast.source.contract.SourceReadRejection as DomainSourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadResult as DomainSourceReadResult
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSelectorTokenCodec
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.SourceTextWithheldReason
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Canonical protocol/domain adapter for the sole authoritative bounded source read. */
class CanonicalSourceReadProtocol(
    private val operations: SourceReadOperations,
    private val authority: QueryReferenceAuthority,
) {
    suspend fun execute(
        request: SourceReadRequest,
        current: SemanticReadAuthority,
        budget: SourceProtocolBudget,
    ): OperationOutcome<ProtocolSourceReadResult, SourceReadQualification, SourceReadCause> {
        val domainRequest =
            when (val admitted = request.admit(authority, current, budget)) {
                is SourceRequestAdmission.Admitted -> admitted.request
                is SourceRequestAdmission.Rejected -> return OperationOutcome.Rejected(admitted.reason)
            }
        return when (val result = operations.read(domainRequest)) {
            is DomainSourceReadResult.Rejected -> OperationOutcome.Rejected(result.reason.protocol())
            is DomainSourceReadResult.Complete ->
                when (val projected = result.project(authority)) {
                    is SourceResultProjection.Projected ->
                        OperationOutcome.Complete(
                            EvidenceEnvelope(
                                CanonicalOperation.SOURCE_READ.id,
                                result.snapshot.lease.evidenceBasis(),
                                projected.result.copy(format = request.format),
                            )
                        )
                    SourceResultProjection.Rejected -> contractViolation()
                }
            is DomainSourceReadResult.Qualified ->
                when (val projected = result.project(authority)) {
                    is SourceQualifiedResultProjection.Projected ->
                        OperationOutcome.Qualified(
                            EvidenceEnvelope(
                                CanonicalOperation.SOURCE_READ.id,
                                result.snapshot.lease.evidenceBasis(),
                                projected.result.copy(format = request.format),
                            ),
                            projected.qualification,
                        )
                    is SourceQualifiedResultProjection.Rejected -> contractViolation(projected.obligation)
                }
        }
    }
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

    data class Rejected(val obligation: SourceInternalObligation) : SourceQualifiedResultProjection
}

private fun DomainSourceReadResult.Complete.project(authority: QueryReferenceAuthority): SourceResultProjection =
    protocolResult(snapshot, region, entities, text, authority)?.let(SourceResultProjection::Projected)
        ?: SourceResultProjection.Rejected

private fun DomainSourceReadResult.Qualified.project(
    authority: QueryReferenceAuthority
): SourceQualifiedResultProjection {
    val result =
        protocolResult(snapshot, region, entities, text, authority)
            ?: return SourceQualifiedResultProjection.Rejected(SourceInternalObligation.RESULT_PROJECTION)
    val protocolQualification =
        qualification.protocol(entities.size)
            ?: return SourceQualifiedResultProjection.Rejected(SourceInternalObligation.QUALIFICATION_PROJECTION)
    return SourceQualifiedResultProjection.Projected(result, protocolQualification)
}

private fun protocolResult(
    snapshot: io.github.amichne.kast.source.contract.SourceSnapshot,
    region: io.github.amichne.kast.source.contract.SourceRegion,
    entities: List<SourceEntity>,
    text: SourceTextProjection,
    authority: QueryReferenceAuthority,
): ProtocolSourceReadResult? {
    val snapshotDocument =
        SourceSnapshotDocument(
            canonicalRoot = protocolText(snapshot.lease.workspaceRoot.value) ?: return null,
            context =
                when (val context = snapshot.context) {
                    is SourceReadContext.Published ->
                        SourceSnapshotContextDocument.Published(
                            context.lease.generation,
                            protocolText(context.sourceState.value) ?: return null,
                        )
                    is SourceReadContext.Live ->
                        SourceSnapshotContextDocument.Live(
                            (context.lease.evidenceBasis() as EvidenceBasis.Live).evidence
                        )
                },
            file = protocolText(snapshot.file.path.value) ?: return null,
            textIdentity = protocolText(snapshot.textIdentity.value) ?: return null,
            coordinateUnit = SourceCoordinateUnitDocument.UTF16_CODE_UNIT,
            length = SourceLengthDocument.parse(snapshot.length.value).refinedOrNull() ?: return null,
        )
    val regionDocument =
        SourceRegionDocument(
            kind = region.kind.protocol(),
            selection = region.selector.protocolSelection() ?: return null,
        )
    val entityDocuments = entities.map { it.protocol(authority) ?: return null }
    val boundedEntities = BoundedProtocolList.create(entityDocuments).refinedOrNull() ?: return null
    val textDocument =
        when (text) {
            SourceTextProjection.NotRequested -> SourceTextProjectionDocument.NotRequested
            is SourceTextProjection.Returned ->
                SourceTextProjectionDocument.Returned(
                    text.selector.protocolSelection() ?: return null,
                    ProtocolSourceText.parse(text.text).refinedOrNull() ?: return null,
                    io.github.amichne.kast.protocol.contract.SourceLineRangeDocument.parse(
                            text.lines.startInclusive.value,
                            text.lines.endInclusive.value,
                        )
                        .refinedOrNull() ?: return null,
                )
            is SourceTextProjection.Withheld ->
                SourceTextProjectionDocument.Withheld(
                    when (text.reason) {
                        SourceTextWithheldReason.BYTE_LIMIT_REACHED ->
                            SourceTextWithheldReasonDocument.BYTE_LIMIT_REACHED
                        SourceTextWithheldReason.PROVIDER_UNAVAILABLE ->
                            SourceTextWithheldReasonDocument.PROVIDER_UNAVAILABLE
                    }
                )
        }
    return ProtocolSourceReadResult(snapshotDocument, regionDocument, boundedEntities, textDocument)
}

private fun SourceEntity.protocol(authority: QueryReferenceAuthority): SourceEntityDocument? {
    val depth = SourceNestingDepthDocument.parse(nestingDepth.value).refinedOrNull() ?: return null
    val parent = protocolText(SourceSelectorTokenCodec.encode(parentSelector).value) ?: return null
    val selectionDocument = selector.protocolSelection() ?: return null
    return when (this) {
        is SourceEntity.Declaration ->
            SourceEntityDocument.Declaration(
                kind.protocol(),
                selector.presentName() ?: return null,
                visibility.protocol(),
                depth,
                parent,
                selectionDocument,
                semanticIdentity.protocol(authority) ?: return null,
            )
        is SourceEntity.ValueParameter ->
            SourceEntityDocument.ValueParameter(
                selector.presentName() ?: return null,
                depth,
                parent,
                selectionDocument,
            )
        is SourceEntity.Call ->
            SourceEntityDocument.Call(
                depth,
                parent,
                selectionDocument,
                calleeSelector.protocolSelection() ?: return null,
                target.protocol(authority) ?: return null,
            )
        is SourceEntity.Reference ->
            SourceEntityDocument.Reference(
                selector.presentName() ?: return null,
                depth,
                parent,
                selectionDocument,
                target.protocol(authority) ?: return null,
            )
    }
}

private fun SourceSelector.Entity.presentName(): ProtocolText? =
    when (val value = name) {
        SourceEntityName.Unavailable -> null
        is SourceEntityName.Present -> protocolText(value.value)
    }

private fun DeclarationSemanticIdentity.protocol(
    authority: QueryReferenceAuthority
): SourceDeclarationSemanticIdentityDocument? =
    when (this) {
        is DeclarationSemanticIdentity.Candidate ->
            when (val encoded = authority.issueDeclarationCandidate(selector.selection)) {
                is CandidateSelectorTokenIssuance.Issued ->
                    SourceDeclarationSemanticIdentityDocument.Candidate(encoded.selector)
                is CandidateSelectorTokenIssuance.Rejected -> null
            }
    }

private fun SourceEntityTarget.protocol(authority: QueryReferenceAuthority): SourceEntityTargetDocument? =
    when (this) {
        is SourceEntityTarget.Candidate ->
            when (val encoded = authority.issueDeclarationCandidate(selector.selection)) {
                is CandidateSelectorTokenIssuance.Issued -> SourceEntityTargetDocument.Candidate(encoded.selector)
                is CandidateSelectorTokenIssuance.Rejected -> null
            }
        is SourceEntityTarget.Local ->
            SourceEntityTargetDocument.Local(
                protocolText(SourceSelectorTokenCodec.encode(selector).value) ?: return null
            )
        is SourceEntityTarget.Unresolved -> SourceEntityTargetDocument.Unresolved(reason.protocol())
    }

private fun CompilerUnresolvedReason.protocol(): SourceUnresolvedReasonDocument =
    when (this) {
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

private fun DomainSourceReadQualification.protocol(entityCount: Int): SourceReadQualification? {
    val count = SourceEntityCountDocument.parse(knownMinimumEntityCount.value).refinedOrNull() ?: return null
    val protocolLimitations = limitations.map { it.protocol() }
    val progress = projectProgress(entityCount).refinedOrNull() ?: return null
    return SourceReadQualification.create(count, protocolLimitations, progress).refinedOrNull()
}

private fun SourceReadLimitation.protocol(): SourceReadLimitationDocument =
    when (this) {
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
        io.github.amichne.kast.source.contract.SourceRegionKind.DECLARATION -> SourceRegionKindDocument.DECLARATION
        io.github.amichne.kast.source.contract.SourceRegionKind.CALLABLE_BODY -> SourceRegionKindDocument.CALLABLE_BODY
        io.github.amichne.kast.source.contract.SourceRegionKind.CLASS_BODY -> SourceRegionKindDocument.CLASS_BODY
        io.github.amichne.kast.source.contract.SourceRegionKind.FILE -> SourceRegionKindDocument.FILE
        io.github.amichne.kast.source.contract.SourceRegionKind.WINDOW -> SourceRegionKindDocument.WINDOW
    }

private fun DeclarationKind.protocol(): SourceDeclarationKindDocument =
    when (this) {
        DeclarationKind.CLASSLIKE -> SourceDeclarationKindDocument.CLASSLIKE
        DeclarationKind.CONSTRUCTOR -> SourceDeclarationKindDocument.CONSTRUCTOR
        DeclarationKind.FUNCTION -> SourceDeclarationKindDocument.FUNCTION
        DeclarationKind.PROPERTY -> SourceDeclarationKindDocument.PROPERTY
        DeclarationKind.TYPE_ALIAS -> SourceDeclarationKindDocument.TYPE_ALIAS
    }

private fun DeclarationVisibility.protocol(): SourceDeclarationVisibilityDocument =
    when (this) {
        DeclarationVisibility.PUBLIC -> SourceDeclarationVisibilityDocument.PUBLIC
        DeclarationVisibility.PROTECTED -> SourceDeclarationVisibilityDocument.PROTECTED
        DeclarationVisibility.INTERNAL -> SourceDeclarationVisibilityDocument.INTERNAL
        DeclarationVisibility.PRIVATE -> SourceDeclarationVisibilityDocument.PRIVATE
        DeclarationVisibility.LOCAL -> SourceDeclarationVisibilityDocument.LOCAL
    }

private fun DomainSourceReadRejection.protocol(): SourceReadCause =
    when (this) {
        DomainSourceReadRejection.WORKSPACE_NOT_READY -> SourceReadRejection.WORKSPACE_NOT_READY
        DomainSourceReadRejection.WORKSPACE_ROOT_MISMATCH -> SourceReadRejection.WORKSPACE_ROOT_MISMATCH
        DomainSourceReadRejection.STALE_GENERATION -> SourceReadRejection.STALE_GENERATION
        DomainSourceReadRejection.SOURCE_STATE_MISMATCH -> SourceReadRejection.SOURCE_STATE_MISMATCH
        DomainSourceReadRejection.CANDIDATE_STALE -> SourceReadRejection.CANDIDATE_STALE
        DomainSourceReadRejection.SOURCE_SELECTOR_STALE -> SourceReadRejection.SOURCE_SELECTOR_STALE
        DomainSourceReadRejection.SOURCE_SNAPSHOT_MISMATCH -> SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH
        DomainSourceReadRejection.SOURCE_UNAVAILABLE -> SourceReadRejection.SOURCE_UNAVAILABLE
        DomainSourceReadRejection.CONTINUATION_UNAVAILABLE -> SourceReadRejection.CONTINUATION_UNAVAILABLE
        DomainSourceReadRejection.CONTINUATION_REQUEST_MISMATCH -> SourceReadRejection.CONTINUATION_REQUEST_MISMATCH
        DomainSourceReadRejection.DOCUMENT_DIRTY -> SourceReadRejection.DOCUMENT_DIRTY
        DomainSourceReadRejection.PSI_DOCUMENT_UNCOMMITTED -> SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED
        DomainSourceReadRejection.OUTSIDE_SOURCE_SCOPE -> SourceReadRejection.OUTSIDE_SOURCE_SCOPE
        DomainSourceReadRejection.ANCHOR_NOT_FOUND -> SourceReadRejection.ANCHOR_NOT_FOUND
        DomainSourceReadRejection.AMBIGUOUS_ANCHOR -> SourceReadRejection.AMBIGUOUS_ANCHOR
        DomainSourceReadRejection.REGION_NOT_APPLICABLE -> SourceReadRejection.REGION_NOT_APPLICABLE
        DomainSourceReadRejection.REGION_ABSENT -> SourceReadRejection.REGION_ABSENT
        DomainSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE -> SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE
        DomainSourceReadRejection.CONTRACT_VIOLATION ->
            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.PROVIDER_CONTRACT)
        DomainSourceReadRejection.INTERNAL_CONTEXT_LEASE_MISMATCH ->
            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.CONTEXT_LEASE)
        DomainSourceReadRejection.INTERNAL_SNAPSHOT_CONTEXT_MISMATCH ->
            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.SNAPSHOT_CONTEXT)
        DomainSourceReadRejection.INTERNAL_SCOPE_MISMATCH ->
            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.SNAPSHOT_SCOPE)
        DomainSourceReadRejection.INTERNAL_SNAPSHOT_MISMATCH ->
            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.ANCHOR_SNAPSHOT)
        DomainSourceReadRejection.INTERNAL_VISIBILITY_MISMATCH ->
            SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.DECLARATION_VISIBILITY)
    }

private fun protocolText(raw: String): ProtocolText? = ProtocolText.parse(raw).refinedOrNull()

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

private fun contractViolation(
    obligation: SourceInternalObligation = SourceInternalObligation.RESULT_PROJECTION
): OperationOutcome.Rejected<SourceReadCause> =
    OperationOutcome.Rejected(SourceReadFailureDetail.InternalContractFailure(obligation))
