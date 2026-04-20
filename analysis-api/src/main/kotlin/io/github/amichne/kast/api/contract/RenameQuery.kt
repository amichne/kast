@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class RenameQuery(
    @DocField(description = "File position identifying the symbol to rename.")
    val position: FilePosition,
    @DocField(description = "The new name to assign to the symbol.")
    val newName: String,
    @DocField(description = "When true (default), computes edits without applying them.", defaultValue = "true")
    val dryRun: Boolean = true,
)
