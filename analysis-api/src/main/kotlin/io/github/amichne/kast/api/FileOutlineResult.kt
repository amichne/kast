package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class FileOutlineResult(
    val symbols: List<OutlineSymbol>,
    val schemaVersion: Int = SCHEMA_VERSION,
)
