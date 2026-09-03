package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult as DomainDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRejection as DomainDiscoveryRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest as DomainDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult as DomainDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult as DomainResolutionResult


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
        if (selectors.size != batch.candidates.size) {
            return OperationOutcome.Rejected(SymbolDiscoverRejection.QUERY_REJECTED)
        }
        val documents = batch.candidates.zip(selectors).map { (candidate, selector) ->
            candidate.protocolDocument(selector)
                ?: return OperationOutcome.Rejected(SymbolDiscoverRejection.QUERY_REJECTED)
        }
        val bounded = when (val admitted = BoundedProtocolList.create(documents)) {
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
            is SymbolDiscoveryOutcome.Qualified -> {
                val qualification = when (
                    val refined = SymbolDiscoverQualification.from(
                        outcome.qualifications.values
                            .map(SymbolDiscoveryQualification::protocolLimitation)
                            .toSet(),
                    )
                ) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected ->
                        return OperationOutcome.Rejected(SymbolDiscoverRejection.QUERY_REJECTED)
                }
                OperationOutcome.Qualified(envelope, qualification)
            }
        }
    }
}

/**
 * Proof transition: `SymbolDiscoveryQualification -> SymbolDiscoverLimitation`.
 *
 * Establishes the exhaustive one-to-one public limitation for every domain discovery qualification,
 * so no limitation is collapsed into a generic incomplete state. The mapping is exhaustive over the
 * closed domain enum.
 */
private fun SymbolDiscoveryQualification.protocolLimitation(): SymbolDiscoverLimitation =
    when (this) {
        SymbolDiscoveryQualification.RESULT_LIMIT_REACHED -> SymbolDiscoverLimitation.RESULT_LIMIT
        SymbolDiscoveryQualification.BYTE_LIMIT_REACHED -> SymbolDiscoverLimitation.BYTE_LIMIT
        SymbolDiscoveryQualification.WORK_LIMIT_REACHED -> SymbolDiscoverLimitation.WORK_LIMIT
        SymbolDiscoveryQualification.TIME_LIMIT_REACHED -> SymbolDiscoverLimitation.TIME_LIMIT
        SymbolDiscoveryQualification.DUMB_MODE_TRANSITION -> SymbolDiscoverLimitation.DUMB_MODE_TRANSITION
        SymbolDiscoveryQualification.PROVIDER_FAILURE -> SymbolDiscoverLimitation.PROVIDER_FAILURE
        SymbolDiscoveryQualification.UNSCOPED_PROVIDER -> SymbolDiscoverLimitation.UNSCOPED_PROVIDER
        SymbolDiscoveryQualification.UNSUPPORTED_ITEM -> SymbolDiscoverLimitation.UNSUPPORTED_ITEM
        SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE ->
            SymbolDiscoverLimitation.EXACT_DEFINITION_UNAVAILABLE
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
            is CandidateSelectorLookup.Found -> when (val selector = lookup.selector) {
                is io.github.amichne.kast.symbol.contract.CandidateSelector.Declaration ->
                    selector.selection
                is io.github.amichne.kast.symbol.contract.CandidateSelector.File,
                is io.github.amichne.kast.symbol.contract.CandidateSelector.Range,
                    -> return OperationOutcome.Rejected(
                        SymbolResolveRejection.CANDIDATE_NOT_DECLARATION,
                    )
            }
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
            is DomainDescriptionResult.Described -> {
                val document = result.description.protocolDocument(request.exactSelector)
                    ?: return OperationOutcome.Rejected(SymbolDescribeRejection.NOT_FOUND)
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.SYMBOL_DESCRIBE.id,
                        result.description.selector.lease.generation,
                        SymbolDescribeResult(document),
                    ),
                )
            }
        }
    }
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
