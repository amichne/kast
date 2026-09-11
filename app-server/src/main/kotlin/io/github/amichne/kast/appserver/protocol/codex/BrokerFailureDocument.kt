package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerLimit
import io.github.amichne.kast.appserver.schema.JsonSchemaViolationEvidenceDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Output-only DTO; construction follows the closed broker outcome and preserves existing absent fields. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class BrokerFailureDocument
private constructor(
    val failure: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val corrections: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val outputViolationEvidence: JsonSchemaViolationEvidenceDocument? = null,
) {
    companion object {
        fun from(failure: BrokerFailure): BrokerFailureDocument =
            when (failure) {
                is BrokerFailure.UnknownNamespace -> BrokerFailureDocument("UNKNOWN_NAMESPACE")
                is BrokerFailure.UnknownTool -> BrokerFailureDocument("UNKNOWN_TOOL")
                is BrokerFailure.InvalidArguments ->
                    BrokerFailureDocument("INVALID_ARGUMENTS", corrections = failure.guidance.map { it.value })
                is BrokerFailure.ProviderStartupRejected -> BrokerFailureDocument(failure.code.value)
                is BrokerFailure.ProviderInvocationRejected -> BrokerFailureDocument(failure.code.value)
                is BrokerFailure.OutputContractRejected ->
                    BrokerFailureDocument(
                        "OUTPUT_CONTRACT_REJECTED",
                        outputViolationEvidence = failure.violationEvidence.toDocument(),
                    )
                is BrokerFailure.InvocationCancelled -> BrokerFailureDocument("INVOCATION_CANCELLED")
                is BrokerFailure.Overloaded ->
                    BrokerFailureDocument(
                        when (failure.limit) {
                            BrokerLimit.IN_FLIGHT_CALLS_PER_CONNECTION ->
                                "BROKER_OVERLOADED_IN_FLIGHT_CALLS_PER_CONNECTION"
                            BrokerLimit.IN_FLIGHT_CALLS_PER_PROVIDER -> "BROKER_OVERLOADED_IN_FLIGHT_CALLS_PER_PROVIDER"
                            BrokerLimit.MAXIMUM_TOOL_ARGUMENT_BYTES -> "BROKER_OVERLOADED_MAXIMUM_TOOL_ARGUMENT_BYTES"
                            BrokerLimit.MAXIMUM_TOOL_RESULT_BYTES -> "BROKER_OVERLOADED_MAXIMUM_TOOL_RESULT_BYTES"
                        }
                    )
            }
    }
}
