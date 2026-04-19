@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class TextEdit(
    @DocField(description = "Absolute path of the file to edit.")
    val filePath: String,
    @DocField(description = "Zero-based byte offset of the region start to replace.")
    val startOffset: Int,
    @DocField(description = "Zero-based byte offset one past the region end to replace.")
    val endOffset: Int,
    @DocField(description = "Replacement text to insert at the specified range.")
    val newText: String,
)
