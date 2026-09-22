package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraversalPartialExpansionCliDocument(
    val subject: String,
    val depth: Int,
    val limitations: List<String>,
    val remainder: TraversalExpansionRemainderCliDocument,
    val scope: TraversalExpansionScopeCliDocument = TraversalExpansionScopeCliDocument.PAGE,
)

@Serializable
enum class TraversalExpansionScopeCliDocument {
    @SerialName("page") PAGE
}

@Serializable
enum class TraversalExpansionRemainderCliDocument {
    @SerialName("continuation_retained") CONTINUATION_RETAINED,
    @SerialName("not_explored") NOT_EXPLORED,
}

fun TraversalPartialExpansionDocument.toCliDocument() =
    TraversalPartialExpansionCliDocument(
        subject.value,
        depth.value,
        limitations.map { it.cliName() },
        when (remainder) {
            TraversalExpansionRemainderDocument.CONTINUATION_RETAINED ->
                TraversalExpansionRemainderCliDocument.CONTINUATION_RETAINED
            TraversalExpansionRemainderDocument.NOT_EXPLORED -> TraversalExpansionRemainderCliDocument.NOT_EXPLORED
        },
    )
