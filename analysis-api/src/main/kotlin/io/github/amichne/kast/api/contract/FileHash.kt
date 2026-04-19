@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class FileHash(
    @DocField(description = "Absolute path of the file.")
    val filePath: String,
    @DocField(description = "SHA-256 hex digest of the file content at plan time.")
    val hash: String,
)
