@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class CompletionsQuery(
    @DocField(description = "File position where completions are requested.")
    val position: FilePosition,
    @DocField(description = "Maximum number of completion items to return.", defaultValue = "100")
    val maxResults: Int = 100,
    @DocField(description = "Restrict results to these symbol kinds only.")
    val kindFilter: Set<SymbolKind>? = null,
)
