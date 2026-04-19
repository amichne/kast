@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class FilePosition(
    @DocField(description = "Absolute path to the source file.")
    val filePath: String,
    @DocField(description = "Zero-based byte offset into the file content.")
    val offset: Int,
)
