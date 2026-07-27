@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsQuery(
    @DocField(description = "Absolute paths of the files to analyze for diagnostics.")
    val filePaths: List<String>,
    @DocField(description = "Maximum number of diagnostic records to return.", defaultValue = "500")
    val maxResults: Int = 500,
    @DocField(description = "Opaque continuation token from the preceding diagnostics page.")
    val pageToken: String? = null,
)
