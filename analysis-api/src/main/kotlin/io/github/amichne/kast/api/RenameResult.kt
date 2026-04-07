package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class RenameResult(
    val edits: List<TextEdit>,
    val fileHashes: List<FileHash>,
    val affectedFiles: List<String>,
    val searchScope: SearchScope? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
