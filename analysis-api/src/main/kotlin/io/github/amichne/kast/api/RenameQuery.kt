package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class RenameQuery(
    val position: FilePosition,
    val newName: String,
    val dryRun: Boolean = true,
)
