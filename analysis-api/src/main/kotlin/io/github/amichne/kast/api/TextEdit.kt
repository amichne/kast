package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class TextEdit(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val newText: String,
)
