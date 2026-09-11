package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.protocol.*
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.*

internal class CanonicalSymbolDiscoverHandler(
    private val workspace: WorkspaceInspectionOperations,
    operations: SymbolDiscoveryOperations,
    authority: CanonicalProtocolAuthority,
) :
    OperationHandler<
        SymbolDiscoverRequest,
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
    > {
    private val protocol = CanonicalSymbolDiscoverProtocol(operations, authority)

    override suspend fun execute(
        request: SymbolDiscoverRequest
    ): OperationOutcome<SymbolDiscoverResult, SymbolDiscoverQualification, SymbolDiscoverRejection> {
        val current =
            (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace?.readLease
                ?: return OperationOutcome.Rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY)
        return protocol.execute(request, current, installedDiscoveryProtocolBudget(request.limit.value))
    }
}

internal class CanonicalSymbolInspectHandler(
    operations: SymbolExactOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<SymbolInspectRequest, SymbolInspectResult, SymbolInspectQualification, SymbolInspectRejection> {
    private val protocol = CanonicalSymbolInspectProtocol(operations, authority)

    override suspend fun execute(
        request: SymbolInspectRequest
    ): OperationOutcome<SymbolInspectResult, SymbolInspectQualification, SymbolInspectRejection> {
        val current =
            when (val target = request.target) {
                is SymbolInspectTarget.Candidate ->
                    when (val lookup = authority.candidate(target.selector)) {
                        is CandidateSelectorLookup.Found -> lookup.selector.lease
                        is CandidateSelectorLookup.Rejected ->
                            return OperationOutcome.Rejected(lookup.reason.protocolRejection())
                    }
                is SymbolInspectTarget.Exact ->
                    when (val lookup = authority.exact(target.selector)) {
                        is ExactSelectorLookup.Found -> lookup.selector.lease
                        is ExactSelectorLookup.Rejected ->
                            return OperationOutcome.Rejected(lookup.reason.protocolRejection())
                    }
            }
        return protocol.execute(request, current)
    }
}

private fun SelectorLookupRejection.protocolRejection(): SymbolInspectRejection =
    when (this) {
        SelectorLookupRejection.WRONG_KIND -> SymbolInspectRejection.SELECTOR_WRONG_KIND
        SelectorLookupRejection.MALFORMED -> SymbolInspectRejection.SELECTOR_MALFORMED
        SelectorLookupRejection.STALE -> SymbolInspectRejection.EXACT_SELECTOR_STALE
        SelectorLookupRejection.WORKSPACE_MISMATCH -> SymbolInspectRejection.SELECTOR_WORKSPACE_MISMATCH
    }
