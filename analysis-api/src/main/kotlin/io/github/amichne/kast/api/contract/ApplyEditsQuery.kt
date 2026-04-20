@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class ApplyEditsQuery(
    @DocField(description = "Text edits to apply, typically from a prior rename or code action.")
    val edits: List<TextEdit>,
    @DocField(description = "Expected file hashes for conflict detection before writing.")
    val fileHashes: List<FileHash>,
    @DocField(description = "Optional file create or delete operations to perform.", defaultValue = "emptyList()")
    val fileOperations: List<FileOperation> = emptyList(),
)
