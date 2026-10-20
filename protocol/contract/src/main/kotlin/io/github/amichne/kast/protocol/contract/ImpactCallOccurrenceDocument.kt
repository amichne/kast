@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactCallOccurrenceDocument {
    val range: ImpactSourceRangeDocument
    val executionOwner: ImpactExecutionOwnerDocument

    @Serializable
    @SerialName("DECLARED")
    data class Declared(val invocation: ImpactInvocationReferenceDocument) : ImpactCallOccurrenceDocument {
        override val range
            get() = invocation.range

        override val executionOwner
            get() = invocation.executionOwner
    }
}
