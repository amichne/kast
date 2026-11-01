@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactCallbackSelectionDocument {
    /** Legacy unique execution remains admissible only when exactly one invocation belongs to the body. */
    @Serializable @SerialName("UNIQUE") data object Unique : ImpactCallbackSelectionDocument

    @Serializable
    @SerialName("EXACT")
    data class Exact(val invocation: ImpactCaptureInvocationDocument) : ImpactCallbackSelectionDocument
}

internal fun ImpactExecutionOwnerDocument.Callback.selects(invocation: ImpactCaptureInvocationDocument): Boolean =
    invocation.body == body &&
        invocations.values.count { it.hasSameCaptureIdentity(invocation) } == 1 &&
        when (val selected = selection) {
            ImpactCallbackSelectionDocument.Unique -> invocations.values.count { it.body == body } == 1
            is ImpactCallbackSelectionDocument.Exact -> selected.invocation.hasSameCaptureIdentity(invocation)
        }

internal fun ImpactExecutionOwnerDocument.Callback.admitsSelection(): Boolean =
    when (val selected = selection) {
        ImpactCallbackSelectionDocument.Unique -> invocations.values.count { it.body == body } == 1
        is ImpactCallbackSelectionDocument.Exact -> selects(selected.invocation)
    }
