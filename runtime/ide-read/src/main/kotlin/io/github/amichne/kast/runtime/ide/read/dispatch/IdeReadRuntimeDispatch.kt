package io.github.amichne.kast.runtime.ide.read.dispatch

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.wire.WireFailure
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope

/**
 * Exact four-operation protocol dispatch for the existing IDE Project read runtime.
 *
 * Construction requires one nominal port for every hosted capability. There is no binding
 * collection to omit, duplicate, or extend with a fifth operation.
 */
class IdeReadRuntimeDispatch(
    workspaceInspect: WorkspaceInspectReadPort,
    symbolDiscover: SymbolDiscoverReadPort,
    symbolResolve: SymbolResolveReadPort,
    symbolDescribe: SymbolDescribeReadPort,
) {
    private val workspaceInspect = IdeReadRuntimeBinding.workspaceInspect(workspaceInspect)
    private val symbolDiscover = IdeReadRuntimeBinding.symbolDiscover(symbolDiscover)
    private val symbolResolve = IdeReadRuntimeBinding.symbolResolve(symbolResolve)
    private val symbolDescribe = IdeReadRuntimeBinding.symbolDescribe(symbolDescribe)

    /**
     * Proof transition: `String -> IdeReadRuntimeDispatchResult`.
     *
     * Establishes a structurally admitted canonical request, membership in the exact IDE-hosted
     * capability set, generated request decoding, one nominal port invocation, and generated
     * outcome encoding. [IdeReadRuntimeDispatchFailure] is the closed expected failure. Raw wire
     * documents may enter or leave only at the outer endpoint frame boundary.
     */
    suspend fun dispatch(document: String): IdeReadRuntimeDispatchResult = when (
        val admission = WireRequestEnvelope.admit(document)
    ) {
        is WireRequestAdmission.Rejected -> IdeReadRuntimeDispatchResult.Rejected(
            IdeReadRuntimeDispatchFailure.RequestAdmissionFailed(admission.failure),
        )
        is WireRequestAdmission.Admitted -> when (admission.request.operation) {
            CanonicalOperation.WORKSPACE_INSPECT -> workspaceInspect.dispatch(admission.request)
            CanonicalOperation.SYMBOL_DISCOVER -> symbolDiscover.dispatch(admission.request)
            CanonicalOperation.SYMBOL_RESOLVE -> symbolResolve.dispatch(admission.request)
            CanonicalOperation.SYMBOL_DESCRIBE -> symbolDescribe.dispatch(admission.request)
            CanonicalOperation.TOPOLOGY_BUILD,
            CanonicalOperation.RELATION_READ,
            CanonicalOperation.TRAVERSAL_RUN,
            CanonicalOperation.DIAGNOSTIC_CHECK,
            CanonicalOperation.CHANGE_PLAN,
            CanonicalOperation.CHANGE_APPLY,
            CanonicalOperation.CHANGE_VERIFY,
            CanonicalOperation.CHANGE_RECOVER,
            -> IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.UnsupportedOperation(admission.request.operation),
            )
        }
    }
}

/** Closed transport result for one IDE-hosted read request frame. */
sealed interface IdeReadRuntimeDispatchResult {
    data class Responded(
        val document: String,
    ) : IdeReadRuntimeDispatchResult

    data class Rejected(
        val failure: IdeReadRuntimeDispatchFailure,
    ) : IdeReadRuntimeDispatchResult
}

/** Closed dispatch failures kept distinct from encoded semantic operation rejection outcomes. */
sealed interface IdeReadRuntimeDispatchFailure {
    data class RequestAdmissionFailed(
        val failure: WireFailure,
    ) : IdeReadRuntimeDispatchFailure

    data class UnsupportedOperation(
        val operation: CanonicalOperation,
    ) : IdeReadRuntimeDispatchFailure

    data class RequestDecodingFailed(
        val operation: IdeHostCapability,
        val failure: WireFailure,
    ) : IdeReadRuntimeDispatchFailure

    data class ResponseEncodingFailed(
        val operation: IdeHostCapability,
        val failure: WireFailure,
    ) : IdeReadRuntimeDispatchFailure
}
