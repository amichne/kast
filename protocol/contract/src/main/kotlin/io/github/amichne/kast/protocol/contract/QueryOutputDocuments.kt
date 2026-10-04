package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QuerySymbolFieldDocument {
    @SerialName("name") NAME,
    @SerialName("location") LOCATION,
    @SerialName("signature") SIGNATURE,
    @SerialName("source") SOURCE,
}

@Serializable
sealed interface QueryOutputDocument {
    @Serializable
    @SerialName("IMPACT_WITNESS")
    data class ImpactWitness(val section: ImpactWitnessSectionDocument) : QueryOutputDocument

    @Serializable
    @SerialName("symbols")
    data class Symbols(
        @ProtocolCollectionConstraint(uniqueItems = true) val fields: BoundedProtocolList<QuerySymbolFieldDocument>
    ) : QueryOutputDocument

    @Serializable @SerialName("occurrences") data object Occurrences : QueryOutputDocument

    @Serializable @SerialName("traversal_records") data object TraversalRecords : QueryOutputDocument

    @Serializable @SerialName("binding_rows") data object BindingRows : QueryOutputDocument

    @Serializable @SerialName("VALUE_PATHS") data object ValuePaths : QueryOutputDocument
}
