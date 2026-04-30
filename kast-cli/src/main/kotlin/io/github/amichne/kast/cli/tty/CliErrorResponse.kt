package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal data class CliErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
    val schemaVersion: Int = SCHEMA_VERSION,
)
