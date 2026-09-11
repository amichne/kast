package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface NativeConcurrentResultObservation {
    @Serializable
    @SerialName("document")
    data class Document(val change: NativeSemanticChangeObservation) : NativeConcurrentResultObservation

    @Serializable
    @SerialName("broker-rejected")
    data class BrokerRejected(val failure: NativeBrokerRejection) : NativeConcurrentResultObservation

    @Serializable @SerialName("response-lost") data object ResponseLost : NativeConcurrentResultObservation
}

@Serializable internal data class NativeConcurrentObservation(val results: List<NativeConcurrentResultObservation>)

internal fun NativeToolResult.concurrentObservation(): NativeConcurrentResultObservation =
    when (this) {
        is NativeToolResult.Document ->
            NativeConcurrentResultObservation.Document(nativeSemanticChangeObservation(payload))
        is NativeToolResult.BrokerRejected -> NativeConcurrentResultObservation.BrokerRejected(failure)
        NativeToolResult.ResponseLost -> NativeConcurrentResultObservation.ResponseLost
    }
