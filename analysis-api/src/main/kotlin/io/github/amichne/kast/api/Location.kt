@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class Location(
    @DocField(description = "Absolute path to the source file containing this location.")
    val filePath: String,
    @DocField(description = "Zero-based byte offset of the start of the symbol.")
    val startOffset: Int,
    @DocField(description = "Zero-based byte offset one past the end of the symbol.")
    val endOffset: Int,
    @DocField(description = "One-based line number of the start position.")
    val startLine: Int,
    @DocField(description = "One-based column number of the start position.")
    val startColumn: Int,
    @DocField(description = "Short source text excerpt at the location, typically the containing line.")
    val preview: String,
)
