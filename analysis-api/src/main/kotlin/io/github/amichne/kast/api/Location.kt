package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class Location(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val startColumn: Int,
    val preview: String,
)
