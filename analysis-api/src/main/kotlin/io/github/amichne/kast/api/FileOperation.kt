package io.github.amichne.kast.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface FileOperation {
    val filePath: String

    @Serializable
    @SerialName("create")
    data class CreateFile(
        override val filePath: String,
        val content: String,
    ) : FileOperation

    @Serializable
    @SerialName("delete")
    data class DeleteFile(
        override val filePath: String,
        val expectedHash: String,
    ) : FileOperation
}
