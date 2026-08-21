package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.WireFailure

fun interface CliRequestPreparer<Request : OperationRequest> {
    /**
     * Proof transition: `Request -> CliProjectionPreparation`.
     *
     * Establishes a generated request document and captured outcome decoder for the concrete
     * request type. [CliProjectionFailure.RequestEncodingFailed] is the closed expected failure.
     * Raw wire text may leave only at the UDS exchange boundary.
     */
    fun prepare(request: Request): CliProjectionPreparation
}

fun interface CliOutcomeProjector<
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    > {
    /**
     * Proof transition: `OperationOutcome<Result, Qualification, Rejection> ->
     * ProjectedCliOutcome`.
     *
     * Preserves the closed semantic outcome variant while producing canonical JSON. Raw result
     * extraction is permitted only within this outer presentation boundary.
     */
    fun project(
        outcome: OperationOutcome<Result, Qualification, Rejection>,
    ): ProjectedCliOutcome
}

/** A generated wire binding whose concrete request and outcome types stay captured. */
class TypedCliProjection<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    private val wireBinding: OperationWireBinding<Request, Result, Qualification, Rejection>,
    private val outcomeProjector: CliOutcomeProjector<Result, Qualification, Rejection>,
) : CliRequestPreparer<Request> {
    override fun prepare(request: Request): CliProjectionPreparation {
        val requestDocument = when (val encoded = wireBinding.encodeRequest(request)) {
            is WireEncoding.Encoded -> encoded.document
            is WireEncoding.Rejected -> return CliProjectionPreparation.Rejected(
                CliProjectionFailure.RequestEncodingFailed(wireBinding.operation, encoded.failure),
            )
        }
        return CliProjectionPreparation.Prepared(
            PreparedCliRequest(wireBinding.operation, requestDocument) { response ->
                when (val decoded = wireBinding.decodeOutcome(response)) {
                    is WireDecoding.Decoded -> CliProjectionCompletion.Completed(
                        outcomeProjector.project(decoded.value),
                    )
                    is WireDecoding.Rejected -> CliProjectionCompletion.Rejected(
                        CliProjectionFailure.ResponseDecodingFailed(
                            wireBinding.operation,
                            decoded.failure,
                        ),
                    )
                }
            },
        )
    }
}

class PreparedCliRequest internal constructor(
    val operation: CanonicalOperation,
    val document: String,
    private val completion: (String) -> CliProjectionCompletion,
) {
    /**
     * Proof transition: `String -> CliProjectionCompletion`.
     *
     * Establishes the captured operation's generated outcome types and canonical JSON projection.
     * [CliProjectionFailure.ResponseDecodingFailed] is the closed expected failure. Raw response
     * text may be extracted only at this wire-decoding boundary.
     */
    fun complete(response: String): CliProjectionCompletion = completion(response)
}

sealed interface CliProjectionPreparation {
    data class Prepared(
        val request: PreparedCliRequest,
    ) : CliProjectionPreparation

    data class Rejected(
        val failure: CliProjectionFailure,
    ) : CliProjectionPreparation
}

sealed interface CliProjectionCompletion {
    data class Completed(
        val outcome: ProjectedCliOutcome,
    ) : CliProjectionCompletion

    data class Rejected(
        val failure: CliProjectionFailure,
    ) : CliProjectionCompletion
}

sealed interface CliProjectionFailure {
    data class RequestEncodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : CliProjectionFailure

    data class ResponseDecodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : CliProjectionFailure
}
