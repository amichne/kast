@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class ReplacementPlanQuery(
    @DocField(description = "Exact compiler-resolved identity of the declaration to replace.")
    val target: SymbolIdentity,
    @DocField(description = "One complete proposed Kotlin function declaration.")
    val proposedDeclaration: String,
)
