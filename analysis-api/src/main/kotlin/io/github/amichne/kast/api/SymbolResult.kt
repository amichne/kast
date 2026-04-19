@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class SymbolResult(
    @DocField(description = "The resolved symbol at the queried position.")
    val symbol: Symbol,
    @DocField(description = "Protocol schema version for forward compatibility.")
    val schemaVersion: Int = SCHEMA_VERSION,
)
