package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CompletionsQuery(
    val position: FilePosition,
    val maxResults: Int = 100,
    val kindFilter: Set<SymbolKind>? = null,
)
