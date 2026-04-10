package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSymbolQuery(
    val pattern: String,
    val kind: SymbolKind? = null,
    val maxResults: Int = 100,
    val regex: Boolean = false,
)
