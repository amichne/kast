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
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
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
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.runtime.server.toProtocolCoverage
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
                result.page.records.map { it.related },
                plan,
                emptySet(),
            )
            is DomainTraversalResult.Qualified -> project(
                result.page.records.map { it.related },
                plan,
                result.qualification.limitations,
            )
        }
    }

    private fun project(
        endpoints: List<RelationEndpoint.Resolved>,
        plan: TraversalPlan,
        limitations: Set<TraversalLimitation>,
    ): OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunRejection> {
        val documents = linkedMapOf<String, SymbolDocument>()
        endpoints.forEach { endpoint ->
            val selector = SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence)
            val token = when (val issued = selectors.issueExact(selector)) {
                is HostedExactIssuance.Issued -> issued.token
                HostedExactIssuance.Rejected ->
                    return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
            val document = endpoint.protocolDocument(token)
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            documents.putIfAbsent(token.value, document)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents.values.toList())) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        }
        val envelope = EvidenceEnvelope(
            CanonicalOperation.TRAVERSAL_RUN.id,
            plan.start.lease.generation,
            TraversalRunResult(bounded),
        )
        return if (limitations.isEmpty()) {
            OperationOutcome.Complete(envelope)
        } else {
            OperationOutcome.Qualified(
                envelope,
                when {
                    TraversalLimitation.DEPTH_LIMIT_REACHED in limitations ->
                        TraversalRunQualification.DEPTH_LIMIT
                    TraversalLimitation.RECORD_LIMIT_REACHED in limitations ->
                        TraversalRunQualification.RESULT_LIMIT
                    else -> TraversalRunQualification.COVERAGE_INCOMPLETE
                },
            )
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
                TopologyExtractionFailure.SOURCE_CONTENT_MOVED ->
                    TopologyExtractionRejection.SOURCE_CONTENT_MOVED
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

private fun RelationEndpoint.protocolDocument(token: ProtocolText): SymbolDocument? {
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
    return SymbolDocument(token, kind.protocolKind(), name, qualified, file, sourceRange)
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
