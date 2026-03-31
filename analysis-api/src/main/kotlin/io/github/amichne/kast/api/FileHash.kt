package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class FileHash(
    val filePath: String,
    val hash: String,
)
