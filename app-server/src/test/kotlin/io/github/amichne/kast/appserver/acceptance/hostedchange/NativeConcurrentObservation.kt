package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionFailure
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

    @Serializable
    @SerialName("workspace-rejected")
    data class WorkspaceRejected(val failure: WorkspaceExecutionFailure) : NativeConcurrentResultObservation

    @Serializable @SerialName("response-lost") data object ResponseLost : NativeConcurrentResultObservation
}

@Serializable internal data class NativeConcurrentObservation(val results: List<NativeConcurrentResultObservation>)

internal fun NativeToolResult.concurrentObservation(): NativeConcurrentResultObservation =
    when (this) {
        is NativeToolResult.Document ->
            NativeConcurrentResultObservation.Document(nativeSemanticChangeObservation(payload))
        is NativeToolResult.BrokerRejected -> NativeConcurrentResultObservation.BrokerRejected(failure)
        is NativeToolResult.WorkspaceRejected -> NativeConcurrentResultObservation.WorkspaceRejected(failure)
        NativeToolResult.ResponseLost -> NativeConcurrentResultObservation.ResponseLost
    }
