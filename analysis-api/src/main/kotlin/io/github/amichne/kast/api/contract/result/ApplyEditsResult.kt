@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class ApplyEditsResult(
    @DocField(description = "Text edits that were successfully applied.")
    val applied: List<TextEdit>,
    @DocField(description = "Absolute paths of all files that were modified.")
    val affectedFiles: List<String>,
    @DocField(description = "Absolute paths of files created by file operations.", defaultValue = "emptyList()")
    val createdFiles: List<String> = emptyList(),
    @DocField(description = "Absolute paths of files deleted by file operations.", defaultValue = "emptyList()")
    val deletedFiles: List<String> = emptyList(),
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
