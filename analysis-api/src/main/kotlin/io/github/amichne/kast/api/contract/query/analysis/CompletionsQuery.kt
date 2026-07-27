@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

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
