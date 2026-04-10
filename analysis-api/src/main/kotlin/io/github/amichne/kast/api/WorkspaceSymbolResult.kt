package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSymbolResult(
    val symbols: List<Symbol>,
    val page: PageInfo? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
