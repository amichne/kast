package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ImpactProducerExecutionStageDocument { INITIAL_EXECUTION }

@Serializable
sealed interface ImpactProducerExecutionFailureDocument {
    @Serializable
    @SerialName("NATIVE")
    data class Native(val cause: ImpactNativeReadRejectionDocument) : ImpactProducerExecutionFailureDocument

    @Serializable
    @SerialName("UNSUPPORTED")
    data class Unsupported(
        val cause: ImpactFlowUnsupportedDocument,
        @SerialName("callback_failures") val callbackFailures: BoundedProtocolList<ImpactCallbackInputFailureDocument>,
    ) : ImpactProducerExecutionFailureDocument
}
