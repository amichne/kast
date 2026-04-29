@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSymbolResult(
    @DocField(description = "Symbols matching the search pattern.")
    val symbols: List<Symbol>,
    @DocField(description = "Pagination metadata when results are truncated.")
    val page: PageInfo? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
