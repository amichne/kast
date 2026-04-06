package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ApplyEditsQuery(
    val edits: List<TextEdit>,
    val fileHashes: List<FileHash>,
    val fileOperations: List<FileOperation> = emptyList(),
)
