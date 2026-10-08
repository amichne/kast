package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.wire.WireFailure
import io.github.amichne.kast.protocol.wire.presentation.OperationProjectionFailure
import java.io.PrintStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class ExistingIdeResponseOperation {
    STATUS,
    CLASSES,
    SUPERTYPE,
    QUERY_RUN,
    SOURCE_READ,
    DIAGNOSTIC_CHECK,
    CHANGE_PLAN,
    CHANGE_APPLY,
    CHANGE_RECOVER,
}

internal fun ExistingIdeOperation.responseOperation(): ExistingIdeResponseOperation =
    when (this) {
        ExistingIdeOperation.Status -> ExistingIdeResponseOperation.STATUS
        is ExistingIdeOperation.Classes -> ExistingIdeResponseOperation.CLASSES
        is ExistingIdeOperation.Supertype -> ExistingIdeResponseOperation.SUPERTYPE
        is ExistingIdeOperation.Read ->
            when (kind) {
                ExistingIdeReadOperation.QUERY_RUN -> ExistingIdeResponseOperation.QUERY_RUN
                ExistingIdeReadOperation.SOURCE_READ -> ExistingIdeResponseOperation.SOURCE_READ
                ExistingIdeReadOperation.DIAGNOSTIC_CHECK -> ExistingIdeResponseOperation.DIAGNOSTIC_CHECK
            }
        is ExistingIdeOperation.Plan -> ExistingIdeResponseOperation.CHANGE_PLAN
        is ExistingIdeOperation.Mutation ->
            when (kind) {
                HostedMutationOperation.CHANGE_APPLY -> ExistingIdeResponseOperation.CHANGE_APPLY
                HostedMutationOperation.CHANGE_RECOVER -> ExistingIdeResponseOperation.CHANGE_RECOVER
            }
    }

@Serializable
internal enum class ExistingIdeResponseFrameOutcome {
    ADMITTED,
    NONPOSITIVE,
    OVER_LIMIT,
}

@Serializable
internal enum class ExistingIdeResponseBodyOutcome {
    DRAINED,
    TRUNCATED,
}

@Serializable
internal enum class ExistingIdeResponseDecodeOutcome {
    RECEIVED,
    SEMANTIC,
    HOST_REJECTED,
}

@Serializable
internal enum class ExistingIdeResponseStage {
    STRICT_JSON,
    CANONICAL_WIRE,
    LIVE_BASIS,
    PREPARED_COMPLETION,
    RESPONSE_ADMISSION,
}

@Serializable
internal enum class ExistingIdeResponseBasisFailure {
    PUBLISHED,
    ROOT_MISMATCH,
    HOST_MISMATCH,
}

/** Private byte and finite admission evidence. No body, paths, handles or exception text. */
@Serializable
internal sealed interface ExistingIdeResponseEvidence {
    @Serializable
    @SerialName("HOST_RESPONSE_FRAME")
    data class Frame(val announcedBytes: Int, val maximumBytes: Int, val outcome: ExistingIdeResponseFrameOutcome) :
        ExistingIdeResponseEvidence

    @Serializable
    @SerialName("HOST_RESPONSE_BODY")
    data class Body(val announcedBytes: Int, val drainedBytes: Int, val outcome: ExistingIdeResponseBodyOutcome) :
        ExistingIdeResponseEvidence

    @Serializable
    @SerialName("HOST_RESPONSE_DECODED")
    data class Decoded(val outcome: ExistingIdeResponseDecodeOutcome) : ExistingIdeResponseEvidence

    @Serializable
    @SerialName("HOST_RESPONSE_REJECTED")
    data class Rejected(
        val stage: ExistingIdeResponseStage,
        val failure: ExistingIdeFailure,
        val cause: ExistingIdeResponseCause,
    ) : ExistingIdeResponseEvidence
}

@Serializable
internal data class ExistingIdeResponseActivity(
    val hostPid: Long,
    val operation: ExistingIdeResponseOperation,
    val evidence: ExistingIdeResponseEvidence,
)

internal fun interface ExistingIdeResponseObserver {
    fun record(evidence: ExistingIdeResponseEvidence)
}

internal class JsonLineExistingIdeResponseObserver(
    private val descriptor: ExistingIdeDescriptor,
    private val operation: ExistingIdeOperation,
    private val output: PrintStream,
) : ExistingIdeResponseObserver {
    override fun record(evidence: ExistingIdeResponseEvidence) {
        output.println(
            responseEvidenceJson.encodeToString(
                ExistingIdeResponseActivity.serializer(),
                ExistingIdeResponseActivity(descriptor.hostPid, operation.responseOperation(), evidence),
            )
        )
    }
}

internal val responseEvidenceJson = Json { encodeDefaults = true }

/** A pure decoder retains its exact finite cause before the effect adapter projects it. */
internal sealed interface ExistingIdeResponseDecodeFailure {
    val stage: ExistingIdeResponseStage
    val failure: ExistingIdeFailure
        get() = ExistingIdeFailure.RESPONSE_REJECTED

    val cause: ExistingIdeResponseCause

    data object StrictJson : ExistingIdeResponseDecodeFailure {
        override val stage = ExistingIdeResponseStage.STRICT_JSON
        override val cause = ExistingIdeResponseCause.StrictJson
    }

    data class Wire(val original: WireFailure) : ExistingIdeResponseDecodeFailure {
        override val stage = ExistingIdeResponseStage.CANONICAL_WIRE
        override val cause
            get() = original.responseCause()
    }

    data class Basis(val reason: ExistingIdeResponseBasisFailure) : ExistingIdeResponseDecodeFailure {
        override val stage = ExistingIdeResponseStage.LIVE_BASIS
        override val cause = ExistingIdeResponseCause.Basis(reason)
    }

    data class Completion(val original: OperationProjectionFailure) : ExistingIdeResponseDecodeFailure {
        override val stage = ExistingIdeResponseStage.PREPARED_COMPLETION
        override val cause =
            when (original) {
                is OperationProjectionFailure.RequestEncodingFailed ->
                    ExistingIdeResponseCause.Completion(
                        original.operation,
                        ExistingIdeCompletionFailureKind.REQUEST_ENCODING_FAILED,
                        original.failure.responseCause(),
                    )
                is OperationProjectionFailure.ResponseDecodingFailed ->
                    ExistingIdeResponseCause.Completion(
                        original.operation,
                        ExistingIdeCompletionFailureKind.RESPONSE_DECODING_FAILED,
                        original.failure.responseCause(),
                    )
            }
    }

    data class Admission(override val failure: ExistingIdeFailure) : ExistingIdeResponseDecodeFailure {
        override val stage = ExistingIdeResponseStage.RESPONSE_ADMISSION
        override val cause = ExistingIdeResponseCause.Admission(failure)
    }
}

internal class ExistingIdeDecodedResponse
private constructor(
    val exchange: ExistingIdeExchange,
    val evidence: ExistingIdeResponseEvidence,
) {
    companion object {
        fun rejected(cause: ExistingIdeResponseDecodeFailure): ExistingIdeDecodedResponse =
            withExchange(ExistingIdeExchange.Rejected(cause.failure), cause)

        fun withExchange(
            exchange: ExistingIdeExchange,
            cause: ExistingIdeResponseDecodeFailure,
        ): ExistingIdeDecodedResponse =
            if (exchange is ExistingIdeExchange.Rejected)
                ExistingIdeDecodedResponse(
                    exchange,
                    ExistingIdeResponseEvidence.Rejected(cause.stage, exchange.failure, cause.cause),
                )
            else fromExchange(exchange)

        fun fromExchange(exchange: ExistingIdeExchange): ExistingIdeDecodedResponse =
            when (exchange) {
                is ExistingIdeExchange.Rejected ->
                    rejected(ExistingIdeResponseDecodeFailure.Admission(exchange.failure))
                is ExistingIdeExchange.Received ->
                    ExistingIdeDecodedResponse(
                        exchange,
                        ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.RECEIVED),
                    )
                is ExistingIdeExchange.Semantic ->
                    ExistingIdeDecodedResponse(
                        exchange,
                        ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.SEMANTIC),
                    )
                is ExistingIdeExchange.HostRejected ->
                    ExistingIdeDecodedResponse(
                        exchange,
                        ExistingIdeResponseEvidence.Decoded(ExistingIdeResponseDecodeOutcome.HOST_REJECTED),
                    )
            }
    }
}
