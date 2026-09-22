package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.WireFailure

fun interface OperationRequestPreparer<Request : OperationRequest> {
    /**
     * Proof transition: `Request -> OperationPreparation`.
     *
     * Establishes a generated request document and captured outcome decoder for the concrete request type.
     * [OperationProjectionFailure.RequestEncodingFailed] is the closed expected failure. Raw wire text may leave only
     * at the UDS exchange boundary.
     */
    fun prepare(request: Request): OperationPreparation
}

fun interface OperationOutcomeProjector<
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
> {
    /**
     * Proof transition: `OperationOutcome<Result, Qualification, Rejection> -> ProjectedOperationOutcome`.
     *
     * Preserves the closed semantic outcome variant while producing canonical JSON. Raw result extraction is permitted
     * only within this outer presentation boundary.
     */
    fun project(outcome: OperationOutcome<Result, Qualification, Rejection>): ProjectedOperationOutcome
}

/** A generated wire binding whose concrete request and outcome types stay captured. */
class TypedOperationProjection<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
>(
    private val wireBinding: OperationWireBinding<Request, Result, Qualification, Rejection>,
    private val outcomeProjector: OperationOutcomeProjector<Result, Qualification, Rejection>,
) : OperationRequestPreparer<Request> {
    override fun prepare(request: Request): OperationPreparation {
        val requestDocument =
            when (val encoded = wireBinding.encodeRequest(request)) {
                is WireEncoding.Encoded -> encoded.document
                is WireEncoding.Rejected ->
                    return OperationPreparation.Rejected(
                        OperationProjectionFailure.RequestEncodingFailed(wireBinding.operation, encoded.failure)
                    )
            }
        return OperationPreparation.Prepared(
            PreparedOperationRequest(
                wireBinding.operation,
                hostedEffect(wireBinding.operation, request),
                requestDocument,
            ) { response ->
                when (val decoded = wireBinding.decodeOutcome(response)) {
                    is WireDecoding.Decoded -> OperationCompletion.Completed(outcomeProjector.project(decoded.value))
                    is WireDecoding.Rejected ->
                        OperationCompletion.Rejected(
                            OperationProjectionFailure.ResponseDecodingFailed(
                                wireBinding.operation,
                                decoded.failure,
                            )
                        )
                }
            }
        )
    }
}

class PreparedOperationRequest
internal constructor(
    val operation: CanonicalOperation,
    val hostedEffect: HostedRequestEffect,
    val document: String,
    private val completion: (String) -> OperationCompletion,
) {
    /**
     * Proof transition: `String -> OperationCompletion`.
     *
     * Establishes the captured operation's generated outcome types and canonical JSON projection.
     * [OperationProjectionFailure.ResponseDecodingFailed] is the closed expected failure. Raw response text may be
     * extracted only at this wire-decoding boundary.
     */
    fun complete(response: String): OperationCompletion = completion(response)
}

private fun hostedEffect(
    operation: CanonicalOperation,
    request: OperationRequest,
): HostedRequestEffect =
    if (operation == CanonicalOperation.CHANGE_PLAN && request is ChangePlanRequest) {
        HostedRequestEffect.ChangePlan(request.intent)
    } else {
        HostedRequestEffect.Operation(operation)
    }

sealed interface OperationPreparation {
    data class Prepared(val request: PreparedOperationRequest) : OperationPreparation

    data class Rejected(val failure: OperationProjectionFailure) : OperationPreparation
}

sealed interface OperationCompletion {
    data class Completed(val outcome: ProjectedOperationOutcome) : OperationCompletion

    data class Rejected(val failure: OperationProjectionFailure) : OperationCompletion
}

sealed interface OperationProjectionFailure {
    data class RequestEncodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : OperationProjectionFailure

    data class ResponseDecodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : OperationProjectionFailure
}
