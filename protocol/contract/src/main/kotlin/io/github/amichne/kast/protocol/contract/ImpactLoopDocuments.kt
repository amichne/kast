@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Detached compiler selections. Shape validation cannot re-resolve operator names or execution. */
@Serializable
data class ImpactLoopProtocolDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val iterator: ImpactInvocationReferenceDocument,
    val hasNext: ImpactInvocationReferenceDocument,
    val next: ImpactInvocationReferenceDocument,
)

@Serializable
enum class ImpactLoopCompletionDocument {
    NORMAL_TRUE
}

/** Conditional provenance only: no repetition, reachability, or termination assertion. */
@Serializable
data class ImpactLoopIterationDocument(
    val protocol: ImpactLoopProtocolDocument,
    @Required val condition: ImpactLoopCompletionDocument = ImpactLoopCompletionDocument.NORMAL_TRUE,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactLoopResultDocument {
    @Serializable
    @SerialName("ITERATOR")
    data class Iterator(val protocol: ImpactLoopProtocolDocument) : ImpactLoopResultDocument

    @Serializable
    @SerialName("ELEMENT")
    data class Element(val iteration: ImpactLoopIterationDocument) : ImpactLoopResultDocument
}

val ImpactLoopResultDocument.protocol: ImpactLoopProtocolDocument
    get() =
        when (this) {
            is ImpactLoopResultDocument.Iterator -> protocol
            is ImpactLoopResultDocument.Element -> iteration.protocol
        }

val ImpactLoopResultDocument.invocation: ImpactInvocationReferenceDocument
    get() =
        when (this) {
            is ImpactLoopResultDocument.Iterator -> protocol.iterator
            is ImpactLoopResultDocument.Element -> iteration.protocol.next
        }
