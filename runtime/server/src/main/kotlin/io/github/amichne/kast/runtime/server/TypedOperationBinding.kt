package io.github.amichne.kast.runtime.server

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.wire.AdmittedWireRequest
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding

/** Narrow executable authority for one typed public operation. */
fun interface OperationHandler<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    > {
    suspend fun execute(
        request: Request,
    ): OperationOutcome<Result, Qualification, Rejection>
}

/**
 * One generated wire binding paired with a handler whose generic request and outcome types match
 * by construction.
 */
class TypedOperationBinding<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    val wireBinding: OperationWireBinding<Request, Result, Qualification, Rejection>,
    private val handler: OperationHandler<Request, Result, Qualification, Rejection>,
) {
    val operation: CanonicalOperation
        get() = wireBinding.operation

    /**
     * Proof transition: `TypedOperationBinding<Request, Result, Qualification, Rejection> ->
     * RuntimeDispatchBinding`.
     *
     * Preserves the exact generated decoder, handler request, canonical outcome, and encoder
     * association inside a closed dispatch capability without exposing `Any` or unchecked casts.
     * [ServerDispatchFailure] is the closed expected failure. The operation-specific request may
     * leave this capability only through [OperationHandler.execute].
     */
    internal fun dispatchBinding(): RuntimeDispatchBinding = object : RuntimeDispatchBinding {
        override val operation: CanonicalOperation = this@TypedOperationBinding.operation

        override suspend fun dispatch(request: AdmittedWireRequest): ServerDispatch = when (
            val decoding = wireBinding.decodeRequest(request)
        ) {
            is WireDecoding.Rejected -> ServerDispatch.Rejected(
                ServerDispatchFailure.RequestDecodingFailed(operation, decoding.failure),
            )
            is WireDecoding.Decoded -> encode(handler.execute(decoding.value))
        }

        private fun encode(
            outcome: OperationOutcome<Result, Qualification, Rejection>,
        ): ServerDispatch = when (val encoding = wireBinding.encodeOutcome(outcome)) {
            is WireEncoding.Encoded -> ServerDispatch.Responded(encoding.document)
            is WireEncoding.Rejected -> ServerDispatch.Rejected(
                ServerDispatchFailure.ResponseEncodingFailed(operation, encoding.failure),
            )
        }
    }
}

/** Captured generic dispatch association used only by the exact internal binding table. */
internal interface RuntimeDispatchBinding {
    val operation: CanonicalOperation

    suspend fun dispatch(request: AdmittedWireRequest): ServerDispatch
}
