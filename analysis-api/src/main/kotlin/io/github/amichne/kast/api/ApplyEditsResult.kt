@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ApplyEditsResult(
    @DocField(description = "Text edits that were successfully applied.")
    val applied: List<TextEdit>,
    @DocField(description = "Absolute paths of all files that were modified.")
    val affectedFiles: List<String>,
    @DocField(description = "Absolute paths of files created by file operations.")
    val createdFiles: List<String> = emptyList(),
    @DocField(description = "Absolute paths of files deleted by file operations.")
    val deletedFiles: List<String> = emptyList(),
    @DocField(description = "Protocol schema version for forward compatibility.")
    val schemaVersion: Int = SCHEMA_VERSION,
)
