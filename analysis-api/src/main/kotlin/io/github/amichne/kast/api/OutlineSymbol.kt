@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class OutlineSymbol(
    @DocField(description = "The declaration at this outline node.")
    val symbol: Symbol,
    @DocField(description = "Nested declarations contained within this symbol.")
    val children: List<OutlineSymbol> = emptyList(),
)
