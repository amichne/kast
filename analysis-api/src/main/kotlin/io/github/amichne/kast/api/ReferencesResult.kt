package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ReferencesResult(
    val declaration: Symbol? = null,
    val references: List<Location>,
    val page: PageInfo? = null,
    val searchScope: SearchScope? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
