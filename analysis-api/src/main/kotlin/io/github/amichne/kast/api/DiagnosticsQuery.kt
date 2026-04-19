@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsQuery(
    @DocField(description = "Absolute paths of the files to analyze for diagnostics.")
    val filePaths: List<String>,
)
