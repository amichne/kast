package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class FilePosition(
    val filePath: String,
    val offset: Int,
)
