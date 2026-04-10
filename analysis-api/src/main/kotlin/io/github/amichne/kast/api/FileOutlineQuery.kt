package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class FileOutlineQuery(
    val filePath: String,
)
