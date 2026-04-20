@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class ImportOptimizeResult(
    @DocField(description = "Text edits that remove unused imports and sort the remainder.")
    val edits: List<TextEdit>,
    @DocField(description = "File hashes at edit-plan time for conflict detection.")
    val fileHashes: List<FileHash>,
    @DocField(description = "Absolute paths of all files that were modified.")
    val affectedFiles: List<String>,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
