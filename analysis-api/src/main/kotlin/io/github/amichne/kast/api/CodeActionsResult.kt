package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CodeAction(
    val title: String,
    val diagnosticCode: String? = null,
    val edits: List<TextEdit>,
    val fileHashes: List<FileHash>,
)

@Serializable
data class CodeActionsResult(
    val actions: List<CodeAction>,
    val schemaVersion: Int = SCHEMA_VERSION,
)
