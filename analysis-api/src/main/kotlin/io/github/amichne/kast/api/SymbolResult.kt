package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class SymbolResult(
    val symbol: Symbol,
    val schemaVersion: Int = SCHEMA_VERSION,
)
