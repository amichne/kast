// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@file:Suppress("ConstructorParameterNaming")

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class PublicToolContainment {
    @SerialName("DIRECT") DIRECT,
    @SerialName("DESCENDANTS") DESCENDANTS,
}

@Serializable
internal enum class PublicToolKinds {
    @SerialName("CLASSLIKE") CLASSLIKE,
    @SerialName("CONSTRUCTOR") CONSTRUCTOR,
    @SerialName("FUNCTION") FUNCTION,
    @SerialName("PROPERTY") PROPERTY,
    @SerialName("TYPE_ALIAS") TYPE_ALIAS,
}

@Serializable
internal data class PublicToolReadSource(
    val symbolRef: ProtocolText,
    val region: PublicToolRegion? = null,
    val text: PublicToolSourceText? = null,
    val entities: PublicToolSourceEntities? = null,
    val page: PublicToolSourcePage? = null,
    val executionBudget: PublicToolExecutionBudget? = null,
) : PublicToolDocument

@Serializable
internal enum class PublicToolRegion {
    @SerialName("DECLARATION") DECLARATION,
    @SerialName("FILE") FILE,
    @SerialName("CLASS_BODY") CLASS_BODY,
    @SerialName("CALLABLE_BODY") CALLABLE_BODY,
}

@Serializable internal sealed interface PublicToolSourceEntities

@Serializable
@SerialName("MATCHING")
internal data class PublicToolSourceEntitiesMatching(
    val filters: BoundedProtocolList<PublicToolSourceFilter>,
    val containment: PublicToolContainment? = null,
    val limit: Int? = null,
) : PublicToolSourceEntities

@Serializable @SerialName("NONE") internal data object PublicToolSourceEntitiesNone : PublicToolSourceEntities

@Serializable internal sealed interface PublicToolSourceFilter

@Serializable @SerialName("CALLS") internal data object PublicToolSourceFilterCalls : PublicToolSourceFilter

@Serializable
@SerialName("DECLARATIONS")
internal data class PublicToolSourceFilterDeclarations(
    val kinds: BoundedProtocolList<PublicToolKinds>,
    val visibilities: BoundedProtocolList<PublicToolVisibilities>? = null,
) : PublicToolSourceFilter

@Serializable @SerialName("PARAMETERS") internal data object PublicToolSourceFilterParameters : PublicToolSourceFilter

@Serializable @SerialName("REFERENCES") internal data object PublicToolSourceFilterReferences : PublicToolSourceFilter

@Serializable internal sealed interface PublicToolSourcePage

@Serializable
@SerialName("CONTINUE")
internal data class PublicToolSourcePageContinue(val continuation: ProtocolText) : PublicToolSourcePage

@Serializable @SerialName("FIRST") internal data object PublicToolSourcePageFirst : PublicToolSourcePage

@Serializable internal sealed interface PublicToolSourceText

@Serializable
@SerialName("COMPLETE")
internal data class PublicToolSourceTextComplete(val maxBytes: Int? = null) : PublicToolSourceText

@Serializable @SerialName("NONE") internal data object PublicToolSourceTextNone : PublicToolSourceText

@Serializable
@SerialName("WINDOW")
internal data class PublicToolSourceTextWindow(
    val beforeLines: Int? = null,
    val afterLines: Int? = null,
    val maxBytes: Int? = null,
) : PublicToolSourceText

@Serializable
internal enum class PublicToolVisibilities {
    @SerialName("PUBLIC") PUBLIC,
    @SerialName("PROTECTED") PROTECTED,
    @SerialName("INTERNAL") INTERNAL,
    @SerialName("PRIVATE") PRIVATE,
    @SerialName("LOCAL") LOCAL,
}
