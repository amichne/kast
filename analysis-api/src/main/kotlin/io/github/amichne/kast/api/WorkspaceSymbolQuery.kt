@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSymbolQuery(
    @DocField(description = "Search pattern to match against symbol names.")
    val pattern: String,
    @DocField(description = "Filter results to symbols of this kind only.")
    val kind: SymbolKind? = null,
    @DocField(description = "Maximum number of symbols to return.")
    val maxResults: Int = 100,
    @DocField(description = "When true, treats the pattern as a regular expression.")
    val regex: Boolean = false,
    @DocField(description = "When true, populates the declarationScope field on each matched symbol.")
    val includeDeclarationScope: Boolean = false,
)
