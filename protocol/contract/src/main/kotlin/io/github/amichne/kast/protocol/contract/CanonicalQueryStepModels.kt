package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QueryVisibilityDocument {
    @SerialName("public") PUBLIC,
    @SerialName("protected") PROTECTED,
    @SerialName("internal") INTERNAL,
    @SerialName("private") PRIVATE,
    @SerialName("local") LOCAL,
}

@Serializable
sealed interface QueryPredicateDocument {
    @Serializable
    @SerialName("visibility")
    data class Visibility(
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val values: BoundedProtocolList<QueryVisibilityDocument>
    ) : QueryPredicateDocument

    @Serializable
    @SerialName("primitive")
    data class Primitive(
        val field: QueryPrimitiveFieldDocument,
        val operator: QueryPrimitiveOperatorDocument,
        val value: ProtocolText,
    ) : QueryPredicateDocument
}

@Serializable
enum class QueryPrimitiveFieldDocument {
    @SerialName("name") NAME,
    @SerialName("kind") KIND,
    @SerialName("file") FILE,
}

@Serializable
enum class QueryPrimitiveOperatorDocument {
    @SerialName("equals") EQUALS,
    @SerialName("not_equals") NOT_EQUALS,
    @SerialName("starts_with") STARTS_WITH,
    @SerialName("ends_with") ENDS_WITH,
}

@Serializable
sealed interface QueryStepDocument {
    @Serializable @SerialName("where") data class Where(val predicate: QueryPredicateDocument) : QueryStepDocument

    @Serializable @SerialName("related") data class Related(val relation: RelationKindDocument) : QueryStepDocument

    @Serializable @SerialName("distinct") data object Distinct : QueryStepDocument

    @Serializable @SerialName("concat") data class Concat(val input: QueryCompositionInputDocument) : QueryStepDocument

    @Serializable @SerialName("intersect") data class Intersect(val right: QueryFromDocument.Result) : QueryStepDocument

    @Serializable @SerialName("union") data class Union(val right: QueryFromDocument.Result) : QueryStepDocument

    @Serializable
    @SerialName("difference")
    data class Difference(val right: QueryFromDocument.Result) : QueryStepDocument
}
