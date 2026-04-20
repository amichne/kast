@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class ReferencesResult(
    @DocField(description = "The resolved declaration symbol, included when `includeDeclaration` was set.")
    val declaration: Symbol? = null,
    @DocField(description = "List of source locations where the symbol is referenced.")
    val references: List<Location>,
    @DocField(description = "Pagination metadata when results are truncated.")
    val page: PageInfo? = null,
    @DocField(description = "Describes the scope and exhaustiveness of the search.")
    val searchScope: SearchScope? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
