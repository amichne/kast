package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryResult
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryWire
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    class ChangeRejected(val failure: HostedChangeFailure) : HostedResponse {
        override val document = Json { encodeDefaults = true }.encodeToString(HostedChangeRejectionDocument(failure))
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
                            Oversized(binding.operation, semantic)
                        } else Canonical(binding.operation, semantic, encoded.document)
                }
        }
    }

    class Oversized(
        val operation: CanonicalOperation,
        val semantic: OperationOutcome<OperationResult, OperationQualification, OperationRejection>,
    ) : HostedResponse {
        override val document = HostedRequests.rejected(HostedEndpointFailure.RESULT_TOO_LARGE)
        override val outcome = HostedEvaluationOutcome.REJECTED
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
