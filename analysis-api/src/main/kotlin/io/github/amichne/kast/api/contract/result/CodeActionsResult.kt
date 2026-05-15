@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class CodeAction(
    @DocField(description = "Human-readable title describing the code action.")
    val title: String,
    @DocField(description = "Diagnostic code this action addresses, if applicable.")
    val diagnosticCode: String? = null,
    @DocField(description = "Text edits that implement this code action.")
    val edits: List<TextEdit>,
    @DocField(description = "File hashes for conflict detection when applying edits.")
    val fileHashes: List<FileHash>,
)

@Serializable
data class CodeActionsResult(
    @DocField(description = "Available code actions at the queried position.")
    val actions: List<CodeAction>,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
