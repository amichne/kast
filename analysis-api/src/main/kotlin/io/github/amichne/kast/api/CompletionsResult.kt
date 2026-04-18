package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CompletionItem(
    val name: String,
    val fqName: String,
    val kind: SymbolKind,
    val type: String? = null,
    val parameters: List<ParameterInfo>? = null,
    val documentation: String? = null,
)

@Serializable
data class CompletionsResult(
    val items: List<CompletionItem>,
    val exhaustive: Boolean = true,
    val schemaVersion: Int = SCHEMA_VERSION,
)
