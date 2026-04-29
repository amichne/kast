@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ParameterInfo
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class CompletionItem(
    @DocField(description = "Simple name of the completion candidate.")
    val name: String,
    @DocField(description = "Fully qualified name of the completion candidate.")
    val fqName: String,
    @DocField(description = "Symbol kind of the completion candidate.")
    val kind: SymbolKind,
    @DocField(description = "Type of the symbol for properties and parameters.")
    val type: String? = null,
    @DocField(description = "Parameter list for function and method completions.")
    val parameters: List<ParameterInfo>? = null,
    @DocField(description = "KDoc documentation for the completion candidate.")
    val documentation: String? = null,
)

@Serializable
data class CompletionsResult(
    @DocField(description = "Completion candidates available at the queried position.")
    val items: List<CompletionItem>,
    @DocField(description = "True when all candidates were returned within maxResults.", defaultValue = "true")
    val exhaustive: Boolean = true,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
