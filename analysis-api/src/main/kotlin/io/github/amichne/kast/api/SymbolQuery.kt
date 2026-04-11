package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class SymbolQuery(
    val position: FilePosition,
    val includeDeclarationScope: Boolean = false,
)
