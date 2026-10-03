@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class QueryRefinementLocationCliDocument(val file: String, val offset: Int)

@Serializable
internal sealed interface QueryItemFailureCliDocument {
    @Serializable
    @SerialName("refinement")
    data class Refinement(
        val location: QueryRefinementLocationCliDocument,
        @Serializable(with = QueryExactFailureCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QueryExactFailureDocument,
    ) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("exact-reference")
    data class ExactReference(
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^exact:v[2345]:") val ref: String,
        @Serializable(with = QueryExactFailureCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QueryExactFailureDocument,
    ) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("predicate")
    data class Predicate(
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^exact:v[2345]:") val ref: String,
        @Serializable(with = QueryPredicateFailureCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument,
    ) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("source")
    data class Source(
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^exact:v[2345]:") val ref: String,
        @Serializable(with = QuerySourceFailureCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument,
    ) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("relation")
    data class Relation(
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^exact:v[2345]:") val ref: String,
        @Serializable(with = RelationKindCliSerializer::class)
        val relation: io.github.amichne.kast.protocol.contract.RelationKindDocument,
        @Serializable(with = QueryRelationFailureCliSerializer::class)
        val reason: io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument,
    ) : QueryItemFailureCliDocument

    @Serializable
    @SerialName("walk")
    data class Walk(
        @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^exact:v[2345]:") val ref: String,
        @Serializable(with = RelationKindCliSerializer::class)
        val relation: io.github.amichne.kast.protocol.contract.RelationKindDocument,
        val reason: QueryWalkFailureCliDocument,
    ) : QueryItemFailureCliDocument
}

internal fun QueryItemFailureDocument.toCliDocument(): QueryItemFailureCliDocument =
    when (this) {
        is QueryItemFailureDocument.Refinement ->
            QueryItemFailureCliDocument.Refinement(
                QueryRefinementLocationCliDocument(location.file.value, location.offset.value),
                reason,
            )
        is QueryItemFailureDocument.ExactReference ->
            QueryItemFailureCliDocument.ExactReference(ref.toCliDocument(), reason)
        is QueryItemFailureDocument.Predicate -> QueryItemFailureCliDocument.Predicate(ref.toCliDocument(), reason)
        is QueryItemFailureDocument.Source -> QueryItemFailureCliDocument.Source(ref.toCliDocument(), reason)
        is QueryItemFailureDocument.Relation ->
            QueryItemFailureCliDocument.Relation(
                ref.toCliDocument(),
                relation,
                reason,
            )
        is QueryItemFailureDocument.Walk ->
            QueryItemFailureCliDocument.Walk(ref.toCliDocument(), relation, reason.toQueryCliDocument())
    }
