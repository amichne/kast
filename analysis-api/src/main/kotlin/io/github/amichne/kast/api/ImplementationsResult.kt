package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ImplementationsResult(
    val declaration: Symbol,
    val implementations: List<Symbol>,
    val exhaustive: Boolean = true,
    val schemaVersion: Int = SCHEMA_VERSION,
)
