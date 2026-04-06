package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ImportOptimizeResult(
    val edits: List<TextEdit>,
    val fileHashes: List<FileHash>,
    val affectedFiles: List<String>,
    val schemaVersion: Int = SCHEMA_VERSION,
)
