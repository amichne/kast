package io.github.amichne.kast.cli.codex

import io.github.amichne.kast.cli.WireExchange
import io.github.amichne.kast.cli.WireSession
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
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
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.WireFailure

internal data class CanonicalKastRead<
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    val outcome: OperationOutcome<Result, Qualification, Rejection>,
    val canonicalJson: String,
)

internal sealed interface CanonicalKastReadAttempt<
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    > {
    data class Read<
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
        >(
        val value: CanonicalKastRead<Result, Qualification, Rejection>,
    ) : CanonicalKastReadAttempt<Result, Qualification, Rejection>

    data class Rejected<
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
        >(
        val failure: CanonicalKastExchangeFailure,
    ) : CanonicalKastReadAttempt<Result, Qualification, Rejection>
}

internal sealed interface CanonicalKastExchangeFailure {
    data class RequestEncoding(val failure: WireFailure) : CanonicalKastExchangeFailure
    data class Transport(
        val failure: io.github.amichne.kast.cli.WireTransportFailure,
    ) : CanonicalKastExchangeFailure
    data class ResponseDecoding(val failure: WireFailure) : CanonicalKastExchangeFailure
}

internal interface CanonicalKastReadOperations {
    fun discover(request: SymbolDiscoverRequest): CanonicalKastReadAttempt<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        >

    fun resolve(request: SymbolResolveRequest): CanonicalKastReadAttempt<
        SymbolResolveResult,
        SymbolResolveQualification,
        SymbolResolveRejection,
        >

    fun describe(request: SymbolDescribeRequest): CanonicalKastReadAttempt<
        SymbolDescribeResult,
        SymbolDescribeQualification,
        SymbolDescribeRejection,
        >

    fun relation(request: RelationReadRequest): CanonicalKastReadAttempt<
        RelationReadResult,
        RelationReadQualification,
        RelationReadRejection,
        >
}

/** Direct canonical-wire invocation of the existing exact-root Kast runtime. */
internal class CanonicalWireKastReadOperations(
    private val session: WireSession,
) : CanonicalKastReadOperations {
    override fun discover(request: SymbolDiscoverRequest) = invoke(
        CanonicalOperationWireBindings.symbolDiscover,
        request,
    )

    override fun resolve(request: SymbolResolveRequest) = invoke(
        CanonicalOperationWireBindings.symbolResolve,
        request,
    )

    override fun describe(request: SymbolDescribeRequest) = invoke(
        CanonicalOperationWireBindings.symbolDescribe,
        request,
    )

    override fun relation(request: RelationReadRequest) = invoke(
        CanonicalOperationWireBindings.relationRead,
        request,
    )

    /**
     * Proof transition: `Request -> CanonicalKastReadAttempt<Result, Qualification, Rejection>`.
     *
     * Establishes generated request encoding, one exact UDS exchange, generated outcome decoding,
     * and preservation of the canonical response document. Expected wire failures remain closed
     * in [CanonicalKastExchangeFailure]. Raw JSON leaves only at the UDS boundary.
     */
    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
        > invoke(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        request: Request,
    ): CanonicalKastReadAttempt<Result, Qualification, Rejection> {
        val requestDocument = when (val encoded = binding.encodeRequest(request)) {
            is WireEncoding.Encoded -> encoded.document
            is WireEncoding.Rejected -> return CanonicalKastReadAttempt.Rejected(
                CanonicalKastExchangeFailure.RequestEncoding(encoded.failure),
            )
        }
        val responseDocument = when (val exchange = session.exchange(requestDocument)) {
            is WireExchange.Received -> exchange.document
            is WireExchange.Rejected -> return CanonicalKastReadAttempt.Rejected(
                CanonicalKastExchangeFailure.Transport(exchange.failure),
            )
        }
        return when (val decoded = binding.decodeOutcome(responseDocument)) {
            is WireDecoding.Decoded -> CanonicalKastReadAttempt.Read(
                CanonicalKastRead(decoded.value, responseDocument),
            )
            is WireDecoding.Rejected -> CanonicalKastReadAttempt.Rejected(
                CanonicalKastExchangeFailure.ResponseDecoding(decoded.failure),
            )
        }
    }
}
