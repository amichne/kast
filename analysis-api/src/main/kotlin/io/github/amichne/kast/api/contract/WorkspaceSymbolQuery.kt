@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSymbolQuery(
    @DocField(description = "Search pattern to match against symbol names.")
    val pattern: String,
    @DocField(description = "Filter results to symbols of this kind only.")
    val kind: SymbolKind? = null,
    @DocField(description = "Maximum number of symbols to return.", defaultValue = "100")
    val maxResults: Int = 100,
    @DocField(description = "When true, treats the pattern as a regular expression.", defaultValue = "false")
    val regex: Boolean = false,
    @DocField(description = "When true, populates the declarationScope field on each matched symbol.", defaultValue = "false")
    val includeDeclarationScope: Boolean = false,
)
