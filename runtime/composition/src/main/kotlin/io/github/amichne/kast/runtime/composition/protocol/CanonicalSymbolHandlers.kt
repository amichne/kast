package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult as DomainDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRejection as DomainDiscoveryRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest as DomainDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult as DomainDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult as DomainResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

private const val SYMBOL_DISCOVERY_WORK_MULTIPLIER = 100L
private const val SYMBOL_DISCOVERY_TIME_MILLIS = 30_000L
private const val SYMBOL_DISCOVERY_RETURNED_BYTES = 1_048_576L

internal class CanonicalSymbolDiscoverHandler(
    private val workspace: WorkspaceInspectionOperations,
    private val operations: SymbolDiscoveryOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<
    SymbolDiscoverRequest,
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > {
    override suspend fun execute(request: SymbolDiscoverRequest): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > {
        val ready = when (val state = workspace.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            else -> return OperationOutcome.Rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY)
        }
        val domainRequest = when (val admitted = admitDiscoveryRequest(ready, request)) {
            is DiscoveryRequestAdmission.Admitted -> admitted.request
            DiscoveryRequestAdmission.Rejected ->
                return OperationOutcome.Rejected(SymbolDiscoverRejection.QUERY_REJECTED)
        }
        return when (val result = operations.discover(domainRequest)) {
            is DomainDiscoveryResult.Rejected -> OperationOutcome.Rejected(result.reason.protocol())
            is DomainDiscoveryResult.Discovered -> projectDiscovery(result.outcome)
        }
    }

    private fun projectDiscovery(outcome: SymbolDiscoveryOutcome): OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > {
        val batch = when (outcome) {
            is SymbolDiscoveryOutcome.Complete -> outcome.batch
            is SymbolDiscoveryOutcome.Qualified -> outcome.batch
        }
        val selectors = when (val issuance = authority.issueCandidates(batch)) {
            is CandidateSelectorIssuance.Issued -> issuance.selectors
            is CandidateSelectorIssuance.Rejected ->
                return OperationOutcome.Rejected(SymbolDiscoverRejection.QUERY_REJECTED)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(selectors)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return OperationOutcome.Rejected(SymbolDiscoverRejection.QUERY_REJECTED)
        }
        val envelope = EvidenceEnvelope(
            CanonicalOperation.SYMBOL_DISCOVER.id,
            batch.lease.generation,
            SymbolDiscoverResult(bounded),
        )
        return when (outcome) {
            is SymbolDiscoveryOutcome.Complete -> OperationOutcome.Complete(envelope)
            is SymbolDiscoveryOutcome.Qualified -> OperationOutcome.Qualified(
                envelope,
                if (
                    SymbolDiscoveryQualification.RESULT_LIMIT_REACHED in
                    outcome.qualifications.values
                ) {
                    SymbolDiscoverQualification.RESULT_LIMIT
                } else {
                    SymbolDiscoverQualification.EVIDENCE_INCOMPLETE
                },
            )
        }
    }
}

internal class CanonicalSymbolResolveHandler(
    private val operations: SymbolExactOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<
    SymbolResolveRequest,
    SymbolResolveResult,
    SymbolResolveQualification,
    SymbolResolveRejection,
    > {
    override suspend fun execute(request: SymbolResolveRequest): OperationOutcome<
        SymbolResolveResult,
        SymbolResolveQualification,
        SymbolResolveRejection,
        > {
        val selection = when (val lookup = authority.candidate(request.candidateSelector)) {
            is CandidateSelectorLookup.Found -> lookup.selection
            CandidateSelectorLookup.Missing ->
                return OperationOutcome.Rejected(SymbolResolveRejection.CANDIDATE_STALE)
        }
        return when (val result = operations.resolve(SymbolResolutionRequest(selection))) {
            is DomainResolutionResult.Rejected -> OperationOutcome.Rejected(
                result.reason.resolveProtocol(),
            )
            is DomainResolutionResult.Resolved -> when (
                val issuance = authority.issueExact(result.symbol.selector)
            ) {
                is ExactSelectorIssuance.Issued -> OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.SYMBOL_RESOLVE.id,
                        result.symbol.selector.lease.generation,
                        SymbolResolveResult(issuance.selector),
                    ),
                )
                is ExactSelectorIssuance.Rejected ->
                    OperationOutcome.Rejected(SymbolResolveRejection.AMBIGUOUS)
            }
        }
    }
}

internal class CanonicalSymbolDescribeHandler(
    private val operations: SymbolExactOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<
    SymbolDescribeRequest,
    SymbolDescribeResult,
    SymbolDescribeQualification,
    SymbolDescribeRejection,
    > {
    override suspend fun execute(request: SymbolDescribeRequest): OperationOutcome<
        SymbolDescribeResult,
        SymbolDescribeQualification,
        SymbolDescribeRejection,
        > {
        val selector = when (val lookup = authority.exact(request.exactSelector)) {
            is ExactSelectorLookup.Found -> lookup.selector
            ExactSelectorLookup.Missing ->
                return OperationOutcome.Rejected(SymbolDescribeRejection.SELECTOR_STALE)
        }
        return when (val result = operations.describe(ExactSymbolRequest(selector))) {
            is DomainDescriptionResult.Rejected -> OperationOutcome.Rejected(
                result.reason.describeProtocol(),
            )
            is DomainDescriptionResult.Described -> when (
                val declaration = ProtocolText.parse(result.description.protocolProjection())
            ) {
                is Refinement.Refined -> OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.SYMBOL_DESCRIBE.id,
                        result.description.selector.lease.generation,
                        SymbolDescribeResult(declaration.value),
                    ),
                )
                is Refinement.Rejected ->
                    OperationOutcome.Rejected(SymbolDescribeRejection.NOT_FOUND)
            }
        }
    }
}

private sealed interface DiscoveryRequestAdmission {
    data class Admitted(
        val request: DomainDiscoveryRequest,
    ) : DiscoveryRequestAdmission

    data object Rejected : DiscoveryRequestAdmission
}

/**
 * Proof transition: `(PublishedWorkspace, SymbolDiscoverRequest) -> DiscoveryRequestAdmission`.
 *
 * Admitted establishes the exact current lease, closed workspace scope, parsed pattern, and finite
 * resource/byte limits. Rejected closes every boundary refinement failure. Raw public query and
 * count extraction is confined to this protocol-to-domain transition.
 */
private fun admitDiscoveryRequest(
    workspace: PublishedWorkspace,
    request: SymbolDiscoverRequest,
): DiscoveryRequestAdmission {
    val pattern = when (val parsed = SymbolDiscoveryPattern.parse(request.query.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return DiscoveryRequestAdmission.Rejected
    }
    val results = when (val parsed = ResultLimit.parse(request.limit.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return DiscoveryRequestAdmission.Rejected
    }
    val work = when (
        val parsed = WorkUnitLimit.parse(request.limit.value * SYMBOL_DISCOVERY_WORK_MULTIPLIER)
    ) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return DiscoveryRequestAdmission.Rejected
    }
    val elapsed = when (val parsed = ElapsedTimeLimitMillis.parse(SYMBOL_DISCOVERY_TIME_MILLIS)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return DiscoveryRequestAdmission.Rejected
    }
    val bytes = when (val parsed = SymbolDiscoveryByteLimit.parse(SYMBOL_DISCOVERY_RETURNED_BYTES)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return DiscoveryRequestAdmission.Rejected
    }
    return DiscoveryRequestAdmission.Admitted(
        DomainDiscoveryRequest(
            scope = SymbolSearchScopeRequest(
                workspace.readLease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.INCLUDE,
                ),
            ),
            kind = SymbolDiscoveryKind.SYMBOL,
            pattern = pattern,
            budget = SymbolDiscoveryBudget(ResourceBudget(results, work, elapsed), bytes),
            match = SymbolDiscoveryMatch.FUZZY,
        ),
    )
}

private fun DomainDiscoveryRejection.protocol(): SymbolDiscoverRejection = when (this) {
    DomainDiscoveryRejection.WORKSPACE_NOT_READY,
    DomainDiscoveryRejection.STALE_GENERATION,
        -> SymbolDiscoverRejection.WORKSPACE_NOT_READY
    DomainDiscoveryRejection.SCOPE_REJECTED,
    DomainDiscoveryRejection.WORKSPACE_INDEX_UNAVAILABLE,
    DomainDiscoveryRejection.PROVIDER_UNAVAILABLE,
    DomainDiscoveryRejection.COMPILER_CONTRACT_VIOLATION,
        -> SymbolDiscoverRejection.QUERY_REJECTED
}

private fun SymbolExactRejection.resolveProtocol(): SymbolResolveRejection = when (this) {
    SymbolExactRejection.WORKSPACE_NOT_READY -> SymbolResolveRejection.WORKSPACE_NOT_READY
    SymbolExactRejection.WORKSPACE_ROOT_MISMATCH,
    SymbolExactRejection.STALE_GENERATION,
    SymbolExactRejection.STALE_LOCATION,
    SymbolExactRejection.DECLARATION_MOVED_OR_CHANGED,
        -> SymbolResolveRejection.CANDIDATE_STALE
    SymbolExactRejection.AMBIGUOUS_DECLARATION -> SymbolResolveRejection.AMBIGUOUS
    SymbolExactRejection.SCOPE_REJECTED,
    SymbolExactRejection.WORKSPACE_INDEX_UNAVAILABLE,
    SymbolExactRejection.OUTSIDE_SCOPE,
    SymbolExactRejection.UNSUPPORTED_DECLARATION,
    SymbolExactRejection.COMPILER_IDENTITY_UNAVAILABLE,
    SymbolExactRejection.COMPILER_CONTRACT_VIOLATION,
        -> SymbolResolveRejection.NOT_FOUND
}

private fun SymbolExactRejection.describeProtocol(): SymbolDescribeRejection = when (this) {
    SymbolExactRejection.WORKSPACE_NOT_READY -> SymbolDescribeRejection.WORKSPACE_NOT_READY
    SymbolExactRejection.WORKSPACE_ROOT_MISMATCH,
    SymbolExactRejection.STALE_GENERATION,
    SymbolExactRejection.STALE_LOCATION,
    SymbolExactRejection.DECLARATION_MOVED_OR_CHANGED,
        -> SymbolDescribeRejection.SELECTOR_STALE
    SymbolExactRejection.SCOPE_REJECTED,
    SymbolExactRejection.WORKSPACE_INDEX_UNAVAILABLE,
    SymbolExactRejection.OUTSIDE_SCOPE,
    SymbolExactRejection.AMBIGUOUS_DECLARATION,
    SymbolExactRejection.UNSUPPORTED_DECLARATION,
    SymbolExactRejection.COMPILER_IDENTITY_UNAVAILABLE,
    SymbolExactRejection.COMPILER_CONTRACT_VIOLATION,
        -> SymbolDescribeRejection.NOT_FOUND
}

private fun SymbolDescription.protocolProjection(): String = buildString {
    append(kind.name.lowercase())
    append(' ')
    append(
        when (val qualified = qualifiedIdentity) {
            is ExactDeclarationQualifiedIdentity.Available -> qualified.value
            ExactDeclarationQualifiedIdentity.Unavailable -> name.value
        },
    )
    append(" @ ")
    append(file.stableValue)
    append(':')
    append(range.startInclusive)
    append('-')
    append(range.endExclusive)
}
