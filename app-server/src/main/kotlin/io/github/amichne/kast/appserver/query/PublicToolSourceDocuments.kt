// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("READ_SOURCE")
internal data class PublicToolReadSourceAction(
    val candidateRef: ProtocolText,
    val region: PublicToolRegion? = null,
    val text: PublicToolSourceText? = null,
    val executionBudget: PublicToolExecutionBudget? = null,
) : PublicToolAction

@Serializable @SerialName("COMPLETE") internal data object PublicToolSourceTextComplete : PublicToolSourceText

@Serializable @SerialName("NONE") internal data object PublicToolSourceTextNone : PublicToolSourceText

@Serializable
@SerialName("WINDOW")
internal data class PublicToolSourceTextWindow(
    val beforeLines: Int? = null,
    val afterLines: Int? = null,
) : PublicToolSourceText

@Serializable
internal enum class PublicToolRegion {
    @SerialName("ANCHOR") ANCHOR,
    @SerialName("CALLABLE_BODY") CALLABLE_BODY,
    @SerialName("CLASS_BODY") CLASS_BODY,
    @SerialName("FILE") FILE,
    @SerialName("ENCLOSING_DECLARATION") ENCLOSING_DECLARATION,
    @SerialName("ENCLOSING_CALLABLE_BODY") ENCLOSING_CALLABLE_BODY,
    @SerialName("ENCLOSING_CLASS_BODY") ENCLOSING_CLASS_BODY,
}

@Serializable internal sealed interface PublicToolSourceText
