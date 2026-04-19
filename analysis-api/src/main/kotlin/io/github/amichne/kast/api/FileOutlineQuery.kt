@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class FileOutlineQuery(
    @DocField(description = "Absolute path of the file to outline.")
    val filePath: String,
)
