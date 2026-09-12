package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.workspace.intellij.read.hosted.*

/** An encoded response retains its original outcome until the connection has finished writing. */
internal sealed interface HostedResponse {
    val document: String
    val outcome: HostedEvaluationOutcome

    class Completed internal constructor(override val document: String) : HostedResponse {
        override val outcome = HostedEvaluationOutcome.COMPLETE
    }

    class Rejected(val failure: HostedEndpointFailure) : HostedResponse {
        override val document = HostedRequests.rejected(failure)
        override val outcome = HostedEvaluationOutcome.REJECTED
    }

    class ReadRejected(val failure: HostedQueryFailure, val stage: HostedQueryStage) : HostedResponse {
        override val document = HostedQueryWire.encode(HostedQueryResult.Rejected(failure, stage))
        override val outcome = HostedEvaluationOutcome.REJECTED
    }

    class Canonical<Result : OperationResult, Qualification : OperationQualification, Rejection : OperationRejection>
    private constructor(
        val operation: CanonicalOperation,
        val semantic: OperationOutcome<Result, Qualification, Rejection>,
        override val document: String,
    ) : HostedResponse {
        override val outcome =
            when (semantic) {
                is OperationOutcome.Complete -> HostedEvaluationOutcome.COMPLETE
                is OperationOutcome.Qualified -> HostedEvaluationOutcome.QUALIFIED
                is OperationOutcome.Rejected -> HostedEvaluationOutcome.REJECTED
            }

        companion object {
            fun <
                Request : OperationRequest,
                Result : OperationResult,
                Qualification : OperationQualification,
                Rejection : OperationRejection,
            > encode(
                binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
                semantic: OperationOutcome<Result, Qualification, Rejection>,
                limits: ReadLimits = ReadLimits.Default,
            ): HostedResponse =
                when (val encoded = binding.encodeOutcome(semantic)) {
                    is WireEncoding.Rejected -> EncodingRejected(binding.operation, semantic, encoded.failure)
                    is WireEncoding.Encoded ->
                        if (
                            encoded.document.toByteArray(Charsets.UTF_8).size >
                                limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value
                        ) {
                            Rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
                        } else Canonical(binding.operation, semantic, encoded.document)
                }
        }
    }

    class EncodingRejected
    internal constructor(
        val operation: CanonicalOperation,
        val semantic: OperationOutcome<OperationResult, OperationQualification, OperationRejection>,
        val failure: io.github.amichne.kast.protocol.wire.WireFailure,
    ) : HostedResponse {
        override val document = HostedRequests.rejected(HostedEndpointFailure.RESPONSE_REJECTED)
        override val outcome = HostedEvaluationOutcome.REJECTED
    }
}
