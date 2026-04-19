@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSymbolResult(
    @DocField(description = "Symbols matching the search pattern.")
    val symbols: List<Symbol>,
    @DocField(description = "Pagination metadata when results are truncated.")
    val page: PageInfo? = null,
    @DocField(description = "Protocol schema version for forward compatibility.")
    val schemaVersion: Int = SCHEMA_VERSION,
)
