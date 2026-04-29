@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class RenameResult(
    @DocField(description = "Text edits needed to perform the rename across the workspace.")
    val edits: List<TextEdit>,
    @DocField(description = "File hashes at edit-plan time for conflict detection.")
    val fileHashes: List<FileHash>,
    @DocField(description = "Absolute paths of all files that would be modified.")
    val affectedFiles: List<String>,
    @DocField(description = "Describes the scope and exhaustiveness of the rename search.")
    val searchScope: SearchScope? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
