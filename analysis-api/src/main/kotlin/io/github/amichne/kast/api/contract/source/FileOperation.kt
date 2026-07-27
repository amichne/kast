@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface FileOperation {
    val filePath: String

    @Serializable
    @SerialName("CREATE_FILE")
    data class CreateFile(
        @DocField(description = "Absolute path of the file to create.")
        override val filePath: String,
        @DocField(description = "Full text content to write to the new file.")
        val content: String,
    ) : FileOperation

    @Serializable
    @SerialName("DELETE_FILE")
    data class DeleteFile(
        @DocField(description = "Absolute path of the file to delete.")
        override val filePath: String,
        @DocField(description = "Expected SHA-256 hash for conflict detection before deleting.")
        val expectedHash: String,
    ) : FileOperation
}
