package io.github.amichne.kast.runtime.ide.read.dispatch

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.wire.AdmittedWireRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding

internal class IdeReadRuntimeBinding<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    > private constructor(
    private val capability: IdeHostCapability,
    private val wireBinding: OperationWireBinding<Request, Result, Qualification, Rejection>,
    private val execute: suspend (Request) -> OperationOutcome<Result, Qualification, Rejection>,
) {
    /**
     * Proof transition: `AdmittedWireRequest -> IdeReadRuntimeDispatchResult`.
     *
     * Establishes that the admitted request matches this binding's generated request schema and
     * that exactly this binding's nominal port produced the encoded outcome. Closed decoding and
     * encoding failures remain [IdeReadRuntimeDispatchFailure]. Raw wire content is exposed only
     * by the outer endpoint frame boundary.
     */
    suspend fun dispatch(request: AdmittedWireRequest): IdeReadRuntimeDispatchResult = when (
        val decoding = wireBinding.decodeRequest(request)
    ) {
        is WireDecoding.Rejected -> IdeReadRuntimeDispatchResult.Rejected(
            IdeReadRuntimeDispatchFailure.RequestDecodingFailed(capability, decoding.failure),
        )
        is WireDecoding.Decoded -> encode(execute(decoding.value))
    }

    /** Emits only after decoding has refined raw wire data into this binding's request type. */
    suspend fun dispatchObserved(
        request: AdmittedWireRequest,
        observability: KastObservability,
        spanName: KastSpanName,
        observationOf: (
            OperationOutcome<Result, Qualification, Rejection>,
        ) -> KastSpanObservation,
    ): IdeReadRuntimeDispatchResult = when (val decoding = wireBinding.decodeRequest(request)) {
        is WireDecoding.Rejected -> IdeReadRuntimeDispatchResult.Rejected(
            IdeReadRuntimeDispatchFailure.RequestDecodingFailed(capability, decoding.failure),
        )
        is WireDecoding.Decoded -> observability.inSpan(spanName) { span ->
            val outcome = execute(decoding.value)
            span.observe(observationOf(outcome))
            encode(outcome)
        }
    }

    /**
     * Proof transition: `OperationOutcome<Result, Qualification, Rejection> ->
     * IdeReadRuntimeDispatchResult`.
     *
     * Establishes that the semantic outcome conforms to this binding's generated response schema.
     * Encoding rejection remains [IdeReadRuntimeDispatchFailure.ResponseEncodingFailed]. Raw wire
     * output is exposed only by the outer endpoint frame boundary.
     */
    private fun encode(
        outcome: OperationOutcome<Result, Qualification, Rejection>,
    ): IdeReadRuntimeDispatchResult = when (val encoding = wireBinding.encodeOutcome(outcome)) {
        is WireEncoding.Encoded -> IdeReadRuntimeDispatchResult.Responded(encoding.document)
        is WireEncoding.Rejected -> IdeReadRuntimeDispatchResult.Rejected(
            IdeReadRuntimeDispatchFailure.ResponseEncodingFailed(capability, encoding.failure),
        )
    }

    companion object {
        fun workspaceInspect(port: WorkspaceInspectReadPort) = IdeReadRuntimeBinding(
            capability = IdeHostCapability.WORKSPACE_INSPECT,
            wireBinding = CanonicalOperationWireBindings.workspaceInspect,
            execute = port::execute,
        )

        fun symbolDiscover(port: SymbolDiscoverReadPort) = IdeReadRuntimeBinding(
            capability = IdeHostCapability.SYMBOL_DISCOVER,
            wireBinding = CanonicalOperationWireBindings.symbolDiscover,
            execute = port::execute,
        )

        fun symbolResolve(port: SymbolResolveReadPort) = IdeReadRuntimeBinding(
            capability = IdeHostCapability.SYMBOL_RESOLVE,
            wireBinding = CanonicalOperationWireBindings.symbolResolve,
            execute = port::execute,
        )

        fun symbolDescribe(port: SymbolDescribeReadPort) = IdeReadRuntimeBinding(
            capability = IdeHostCapability.SYMBOL_DESCRIBE,
            wireBinding = CanonicalOperationWireBindings.symbolDescribe,
            execute = port::execute,
        )
    }
}
