package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.TopologyBuildDigest
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult as ProtocolTopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.protocol.contract.TopologyEnumerationRejection
import io.github.amichne.kast.protocol.contract.TopologyExtractionRejection
import io.github.amichne.kast.protocol.contract.TopologyPublicationRejection
import io.github.amichne.kast.protocol.contract.TopologySnapshotRejection
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationFactCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.runtime.server.toProtocolCoverage
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyPublicationFailure
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult as DomainTraversalResult

private const val HOSTED_WORK_MULTIPLIER = 100L
private const val HOSTED_TIME_MILLIS = 30_000L
private const val HOSTED_HOP_TIME_MILLIS = 1_000L
private const val HOSTED_RETURNED_BYTES = 1_048_576L

internal object HostedTopologyProtocol {
    fun bindings(
        operations: HostedTopologyOperations,
        selectors: HostedExactSelectorOperations,
    ): List<TypedOperationBinding<*, *, *, *>> = listOf(
        TypedOperationBinding(
            CanonicalOperationWireBindings.topologyBuild,
            HostedTopologyBuildHandler(operations.build),
        ),
        TypedOperationBinding(
            CanonicalOperationWireBindings.traversalRun,
            HostedTraversalRunHandler(operations.traversal, selectors),
        ),
    )
}

private class HostedTopologyBuildHandler(
    private val operations: TopologyBuildOperations,
) : OperationHandler<
    TopologyBuildRequest,
    ProtocolTopologyBuildResult,
    TopologyBuildQualification,
    TopologyBuildRejection,
    > {
    override suspend fun execute(request: TopologyBuildRequest) = when (val result = operations.build()) {
        is TopologyBuildResult.Published -> complete(result.snapshot, TopologyBuildStatus.PUBLISHED)
        is TopologyBuildResult.Reused -> complete(result.snapshot, TopologyBuildStatus.REUSED)
        TopologyBuildResult.WorkspaceMoved ->
            OperationOutcome.Rejected(TopologyBuildRejection.WorkspaceMoved)
        is TopologyBuildResult.Rejected -> OperationOutcome.Rejected(result.failure.protocol())
    }

    private fun complete(
        snapshot: io.github.amichne.kast.topology.contract.PublishedTopologySnapshot,
        status: TopologyBuildStatus,
    ): OperationOutcome<ProtocolTopologyBuildResult, TopologyBuildQualification, TopologyBuildRejection> {
        val digest = when (val parsed = TopologyBuildDigest.parse(snapshot.manifest.digest.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return OperationOutcome.Rejected(
                TopologyBuildRejection.PublicationFailed(
                    TopologyPublicationRejection.CONTRACT_VIOLATION,
                ),
            )
        }
        return OperationOutcome.Complete(
            EvidenceEnvelope(
                CanonicalOperation.TOPOLOGY_BUILD.id,
                snapshot.identity.lease.generation,
                ProtocolTopologyBuildResult(status, snapshot.identity.lease.generation, digest),
            ),
        )
    }
}

private class HostedTraversalRunHandler(
    private val operations: TraversalOperations,
    private val selectors: HostedExactSelectorOperations,
) : OperationHandler<
    TraversalRunRequest,
    TraversalRunResult,
    TraversalRunQualification,
    TraversalRunRejection,
    > {
    override suspend fun execute(request: TraversalRunRequest): OperationOutcome<
        TraversalRunResult,
        TraversalRunQualification,
        TraversalRunRejection,
        > {
        val selector = when (val lookup = selectors.exact(request.exactSelector)) {
            is HostedExactLookup.Found -> lookup.selector
            HostedExactLookup.Missing ->
                return OperationOutcome.Rejected(TraversalRunRejection.SELECTOR_STALE)
            HostedExactLookup.TopologyUnavailable ->
                return OperationOutcome.Rejected(TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED)
        }
        val budget = traversalBudget(request.maximumDepth.value, request.maximumResults.value)
            ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val plan = when (val admitted = TraversalPlan.start(
            selector,
            request.relation.meaning(),
            budget,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
        return when (val result = operations.run(plan)) {
            is DomainTraversalResult.Rejected -> OperationOutcome.Rejected(result.reason.protocol())
            is DomainTraversalResult.Complete -> project(
                result.page.records,
                plan,
                null,
            )
            is DomainTraversalResult.Qualified -> project(
                result.page.records,
                plan,
                result.qualification,
            )
        }
    }

    private fun project(
        records: List<TraversalRecord>,
        plan: TraversalPlan,
        qualification: TraversalQualification?,
    ): OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunRejection> {
        val documents = mutableListOf<TraversalRecordDocument>()
        records.forEach { record ->
            val relation = record.fact.protocolDocument(selectors)
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            val depth = TraversalDepthDocument.parse(record.depth.value).valueOrNull()
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            documents += TraversalRecordDocument(depth, relation)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
        val envelope = EvidenceEnvelope(
            CanonicalOperation.TRAVERSAL_RUN.id,
            plan.start.lease.generation,
            TraversalRunResult(bounded),
        )
        return if (qualification == null) {
            OperationOutcome.Complete(envelope)
        } else {
            val document = qualification.protocolQualification()
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            OperationOutcome.Qualified(envelope, document)
        }
    }
}

private fun TopologyBuildFailure.protocol(): TopologyBuildRejection = when (this) {
    TopologyBuildFailure.WorkspaceNotReady -> TopologyBuildRejection.WorkspaceNotReady
    TopologyBuildFailure.SnapshotContractViolation ->
        TopologyBuildRejection.SnapshotUnavailable(TopologySnapshotRejection.CONTRACT_VIOLATION)
    is TopologyBuildFailure.SnapshotRead -> TopologyBuildRejection.SnapshotUnavailable(
        when (failure) {
            io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure.STORAGE_UNAVAILABLE ->
                TopologySnapshotRejection.STORAGE_UNAVAILABLE
            io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure.CORRUPT_SNAPSHOT ->
                TopologySnapshotRejection.CORRUPT_SNAPSHOT
        },
    )
    is TopologyBuildFailure.Enumeration -> TopologyBuildRejection.EnumerationFailed(
        when (failure) {
            TopologyCandidateEnumerationFailure.WORKSPACE_UNAVAILABLE ->
                TopologyEnumerationRejection.WORKSPACE_UNAVAILABLE
            TopologyCandidateEnumerationFailure.SOURCE_ROOT_UNAVAILABLE ->
                TopologyEnumerationRejection.SOURCE_ROOT_UNAVAILABLE
            TopologyCandidateEnumerationFailure.SOURCE_CONTENT_UNAVAILABLE ->
                TopologyEnumerationRejection.SOURCE_CONTENT_UNAVAILABLE
            TopologyCandidateEnumerationFailure.AMBIGUOUS_SOURCE_ROOT_OWNER ->
                TopologyEnumerationRejection.AMBIGUOUS_SOURCE_ROOT_OWNER
            TopologyCandidateEnumerationFailure.CANDIDATE_REJECTED ->
                TopologyEnumerationRejection.CANDIDATE_REJECTED
        },
    )
    is TopologyBuildFailure.Extraction -> {
        val file = (ProtocolText.parse(file.value) as? Refinement.Refined)?.value
            ?: return TopologyBuildRejection.ExtractionContractViolation
        TopologyBuildRejection.ExtractionFailed(
            file,
            when (failure) {
                TopologyExtractionFailure.PROJECT_UNAVAILABLE ->
                    TopologyExtractionRejection.PROJECT_UNAVAILABLE
                TopologyExtractionFailure.FILE_UNAVAILABLE ->
                    TopologyExtractionRejection.FILE_UNAVAILABLE
                TopologyExtractionFailure.DOCUMENT_DIRTY ->
                    TopologyExtractionRejection.DOCUMENT_DIRTY
                TopologyExtractionFailure.PSI_DOCUMENT_UNCOMMITTED ->
                    TopologyExtractionRejection.PSI_DOCUMENT_UNCOMMITTED
                TopologyExtractionFailure.VFS_CONTENT_MISMATCH ->
                    TopologyExtractionRejection.VFS_CONTENT_MISMATCH
                TopologyExtractionFailure.SOURCE_CONTENT_CHANGED_DURING_BUILD ->
                    TopologyExtractionRejection.SOURCE_CONTENT_CHANGED_DURING_BUILD
                TopologyExtractionFailure.NOT_KOTLIN_PSI ->
                    TopologyExtractionRejection.NOT_KOTLIN_PSI
                TopologyExtractionFailure.COMPILER_UNAVAILABLE ->
                    TopologyExtractionRejection.COMPILER_UNAVAILABLE
                TopologyExtractionFailure.FACT_REJECTED ->
                    TopologyExtractionRejection.FACT_REJECTED
            },
        )
    }
    TopologyBuildFailure.ExtractionContractViolation ->
        TopologyBuildRejection.ExtractionContractViolation
    is TopologyBuildFailure.Coverage -> when (val projected = failure.toProtocolCoverage()) {
        is Refinement.Refined -> TopologyBuildRejection.CoverageIncomplete(projected.value)
        is Refinement.Rejected -> TopologyBuildRejection.CoverageProjectionFailed(
            projected.failure,
        )
    }
    is TopologyBuildFailure.Publication -> TopologyBuildRejection.PublicationFailed(
        when (failure) {
            TopologyPublicationFailure.STORAGE_UNAVAILABLE ->
                TopologyPublicationRejection.STORAGE_UNAVAILABLE
            TopologyPublicationFailure.SNAPSHOT_CONFLICT ->
                TopologyPublicationRejection.SNAPSHOT_CONFLICT
            TopologyPublicationFailure.CORRUPT_SNAPSHOT ->
                TopologyPublicationRejection.CORRUPT_SNAPSHOT
        },
    )
}

private fun traversalBudget(depth: Int, results: Int): TraversalBudget? {
    val resultLimit = ResultLimit.parse(results).valueOrNull() ?: return null
    val work = WorkUnitLimit.parse(results * HOSTED_WORK_MULTIPLIER).valueOrNull() ?: return null
    val elapsed = ElapsedTimeLimitMillis.parse(HOSTED_TIME_MILLIS).valueOrNull() ?: return null
    val hopElapsed = ElapsedTimeLimitMillis.parse(HOSTED_HOP_TIME_MILLIS).valueOrNull() ?: return null
    val relationBytes = RelationByteLimit.parse(HOSTED_RETURNED_BYTES).valueOrNull() ?: return null
    val bytes = TraversalByteLimit.parse(HOSTED_RETURNED_BYTES).valueOrNull() ?: return null
    val maximumDepth = TraversalDepthLimit.parse(depth).valueOrNull() ?: return null
    val frontier = TraversalFrontierLimit.parse(results).valueOrNull() ?: return null
    val relation = RelationBudget(
        ResourceBudget(resultLimit, work, hopElapsed),
        relationBytes,
    )
    return TraversalBudget(resultLimit, bytes, work, elapsed, maximumDepth, frontier, relation)
}

private fun RelationKindDocument.meaning(): RelationMeaning = when (this) {
    RelationKindDocument.REFERENCES -> RelationMeaning.References
    RelationKindDocument.CALLERS -> RelationMeaning.Callers
    RelationKindDocument.CALLEES -> RelationMeaning.Callees
    RelationKindDocument.IMPLEMENTATIONS -> RelationMeaning.Implementations
    RelationKindDocument.INHERITORS -> RelationMeaning.Inheritors
    RelationKindDocument.OVERRIDES -> RelationMeaning.Overrides
    RelationKindDocument.TYPE_USES -> RelationMeaning.TypeUses
}

private fun TraversalRejection.protocol(): TraversalRunRejection = when (this) {
    is TraversalRejection.OneHopRejected -> when (reason) {
        io.github.amichne.kast.relation.contract.RelationReadRejection.WORKSPACE_NOT_READY ->
            TraversalRunRejection.WORKSPACE_NOT_READY
        io.github.amichne.kast.relation.contract.RelationReadRejection.WORKSPACE_ROOT_MISMATCH,
        io.github.amichne.kast.relation.contract.RelationReadRejection.STALE_GENERATION,
        io.github.amichne.kast.relation.contract.RelationReadRejection.STALE_SELECTOR,
        -> TraversalRunRejection.SELECTOR_STALE
        else -> TraversalRunRejection.PLAN_REJECTED
    }
    TraversalRejection.RequiredEvidenceUnavailable,
    TraversalRejection.RequiredEvidenceStale,
    -> TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED
    TraversalRejection.ReaderContractViolation,
    TraversalRejection.TraversalContractViolation,
    -> TraversalRunRejection.PLAN_REJECTED
}

private fun RelationFact.protocolDocument(
    selectors: HostedExactSelectorOperations,
): RelationFactDocument? {
    val sourceDocument = source.protocolDocument(selectors) ?: return null
    val targetDocument = target.protocolDocument(selectors) ?: return null
    val occurrenceFile = ProtocolText.parse(occurrence.file.stableValue).valueOrNull()
        ?: return null
    val occurrenceStart = ProtocolOffset.parse(occurrence.range.startInclusive).valueOrNull()
        ?: return null
    val occurrenceEnd = ProtocolOffset.parse(occurrence.range.endExclusive).valueOrNull()
        ?: return null
    val occurrenceRange = SourceRangeDocument.create(occurrenceStart, occurrenceEnd).valueOrNull()
        ?: return null
    return RelationFactDocument(
        meaning = meaning.protocolKind(),
        source = sourceDocument,
        target = targetDocument,
        occurrence = RelationOccurrenceDocument(occurrenceFile, occurrenceRange),
        provenance = provenance.protocolDocument(),
        coverage = coverage.protocolDocument(),
    )
}

private fun RelationEndpoint.protocolDocument(
    selectors: HostedExactSelectorOperations,
): SymbolDocument? {
    val selector = when (this) {
        is RelationEndpoint.Subject -> selector
        is RelationEndpoint.Resolved -> SymbolSelector.issue(lease, scope, evidence)
    }
    val token = when (val issued = selectors.issueExact(selector)) {
        is HostedExactIssuance.Issued -> issued.token
        HostedExactIssuance.Rejected -> return null
    }
    val name = ProtocolText.parse(name.value).valueOrNull() ?: return null
    val file = ProtocolText.parse(file.stableValue).valueOrNull() ?: return null
    val start = ProtocolOffset.parse(range.startInclusive).valueOrNull() ?: return null
    val end = ProtocolOffset.parse(range.endExclusive).valueOrNull() ?: return null
    val sourceRange = SourceRangeDocument.create(start, end).valueOrNull() ?: return null
    val qualified = when (val identity = qualifiedIdentity) {
        is ExactDeclarationQualifiedIdentity.Available -> SymbolQualifiedIdentityDocument.Available(
            ProtocolText.parse(identity.value).valueOrNull() ?: return null,
        )
        ExactDeclarationQualifiedIdentity.Unavailable -> SymbolQualifiedIdentityDocument.Unavailable
    }
    val signatureDocument = signature.protocolDocument() ?: return null
    val compilerEvidence = CompilerSymbolEvidenceDocument.restore(
        identity = ProtocolText.parse(compilerIdentity.value).valueOrNull() ?: return null,
        signature = signatureDocument,
    ).valueOrNull() ?: return null
    return SymbolDocument.create(
        selector = token,
        kind = kind.protocolKind(),
        name = name,
        qualifiedIdentity = qualified,
        file = file,
        range = sourceRange,
        compilerEvidence = compilerEvidence,
    ).valueOrNull()
}

private fun CanonicalCompilerSignature.protocolDocument(): CompilerSignatureDocument? =
    when (this) {
        is CanonicalCompilerSignature.Function -> CompilerSignatureDocument.Function(
            qualifiedIdentity = ProtocolText.parse(qualifiedIdentity.value).valueOrNull()
                ?: return null,
            receiver = when (val canonical = receiver) {
                CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
                is CanonicalCompilerReceiver.Present -> CompilerReceiverDocument.Present(
                    ProtocolText.parse(canonical.type.value).valueOrNull() ?: return null,
                )
            },
            contextReceivers = contextReceivers.protocolTypes() ?: return null,
            valueParameters = valueParameters.protocolTypes() ?: return null,
            typeParameterCount = CompilerTypeParameterCountDocument.parse(typeParameterCount.value)
                .valueOrNull() ?: return null,
        )
        is CanonicalCompilerSignature.Property -> CompilerSignatureDocument.Property(
            qualifiedIdentity = ProtocolText.parse(qualifiedIdentity.value).valueOrNull()
                ?: return null,
            receiver = when (val canonical = receiver) {
                CanonicalCompilerReceiver.Absent -> CompilerReceiverDocument.Absent
                is CanonicalCompilerReceiver.Present -> CompilerReceiverDocument.Present(
                    ProtocolText.parse(canonical.type.value).valueOrNull() ?: return null,
                )
            },
            contextReceivers = contextReceivers.protocolTypes() ?: return null,
            returnType = ProtocolText.parse(returnType.value).valueOrNull() ?: return null,
        )
        is CanonicalCompilerSignature.TypeAlias -> CompilerSignatureDocument.TypeAlias(
            ProtocolText.parse(qualifiedIdentity.value).valueOrNull() ?: return null,
        )
        is CanonicalCompilerSignature.ClassLike -> CompilerSignatureDocument.ClassLike(
            ProtocolText.parse(qualifiedIdentity.value).valueOrNull() ?: return null,
        )
    }

private fun List<CanonicalCompilerType>.protocolTypes(): BoundedProtocolList<ProtocolText>? =
    BoundedProtocolList.create(
        map { type -> ProtocolText.parse(type.value).valueOrNull() ?: return null },
    ).valueOrNull()

private fun RelationMeaning.protocolKind(): RelationKindDocument = when (this) {
    RelationMeaning.References -> RelationKindDocument.REFERENCES
    RelationMeaning.Callers -> RelationKindDocument.CALLERS
    RelationMeaning.Callees -> RelationKindDocument.CALLEES
    RelationMeaning.Implementations -> RelationKindDocument.IMPLEMENTATIONS
    RelationMeaning.Inheritors -> RelationKindDocument.INHERITORS
    RelationMeaning.Overrides -> RelationKindDocument.OVERRIDES
    RelationMeaning.TypeUses -> RelationKindDocument.TYPE_USES
}

private fun RelationProvenance.protocolDocument(): RelationProvenanceDocument = when (this) {
    RelationProvenance.K2_AUTHORED_SOURCE -> RelationProvenanceDocument.K2_AUTHORED_SOURCE
    RelationProvenance.K2_GENERATED_SOURCE -> RelationProvenanceDocument.K2_GENERATED_SOURCE
    RelationProvenance.K2_PROJECT_LIBRARY -> RelationProvenanceDocument.K2_PROJECT_LIBRARY
}

private fun RelationFactCoverage.protocolDocument(): RelationFactCoverageDocument = when (this) {
    RelationFactCoverage.EXACT_COMPILER_CONFIRMED ->
        RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED
}

private fun TraversalQualification.protocolQualification(): TraversalRunQualification? {
    val continuation = TraversalContinuationDocument.parse(continuation.fingerprint.value)
        .valueOrNull() ?: return null
    return TraversalRunQualification.create(
        limitations.map(TraversalLimitation::protocolDocument),
        relationLimitations.map(RelationLimitation::protocolDocument),
        continuation,
    ).valueOrNull()
}

private fun RelationLimitation.protocolDocument(): RelationLimitationDocument = when (this) {
    RelationLimitation.RESULT_LIMIT_REACHED -> RelationLimitationDocument.RESULT_LIMIT_REACHED
    RelationLimitation.BYTE_LIMIT_REACHED -> RelationLimitationDocument.BYTE_LIMIT_REACHED
    RelationLimitation.WORK_LIMIT_REACHED -> RelationLimitationDocument.WORK_LIMIT_REACHED
    RelationLimitation.TIME_LIMIT_REACHED -> RelationLimitationDocument.TIME_LIMIT_REACHED
    RelationLimitation.DUMB_MODE_TRANSITION -> RelationLimitationDocument.DUMB_MODE_TRANSITION
    RelationLimitation.UNRESOLVED_TARGET -> RelationLimitationDocument.UNRESOLVED_TARGET
    RelationLimitation.UNSUPPORTED_ITEM -> RelationLimitationDocument.UNSUPPORTED_ITEM
    RelationLimitation.PROVIDER_FAILURE -> RelationLimitationDocument.PROVIDER_FAILURE
    RelationLimitation.PROVIDER_INCOMPLETE -> RelationLimitationDocument.PROVIDER_INCOMPLETE
}

private fun TraversalLimitation.protocolDocument(): TraversalLimitationDocument = when (this) {
    TraversalLimitation.RECORD_LIMIT_REACHED -> TraversalLimitationDocument.RECORD_LIMIT_REACHED
    TraversalLimitation.BYTE_LIMIT_REACHED -> TraversalLimitationDocument.BYTE_LIMIT_REACHED
    TraversalLimitation.WORK_LIMIT_REACHED -> TraversalLimitationDocument.WORK_LIMIT_REACHED
    TraversalLimitation.TIME_LIMIT_REACHED -> TraversalLimitationDocument.TIME_LIMIT_REACHED
    TraversalLimitation.DEPTH_LIMIT_REACHED -> TraversalLimitationDocument.DEPTH_LIMIT_REACHED
    TraversalLimitation.FRONTIER_LIMIT_REACHED -> TraversalLimitationDocument.FRONTIER_LIMIT_REACHED
    TraversalLimitation.ONE_HOP_INCOMPLETE -> TraversalLimitationDocument.ONE_HOP_INCOMPLETE
}

private fun CompilerSymbolKind.protocolKind(): SymbolKindDocument = when (this) {
    CompilerSymbolKind.CLASSLIKE -> SymbolKindDocument.CLASSLIKE
    CompilerSymbolKind.CONSTRUCTOR -> SymbolKindDocument.CONSTRUCTOR
    CompilerSymbolKind.FUNCTION -> SymbolKindDocument.FUNCTION
    CompilerSymbolKind.PROPERTY -> SymbolKindDocument.PROPERTY
    CompilerSymbolKind.TYPE_ALIAS -> SymbolKindDocument.TYPE_ALIAS
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
