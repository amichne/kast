package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class OutlineSymbol(
    val symbol: Symbol,
    val children: List<OutlineSymbol> = emptyList(),
)
