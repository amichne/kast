package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsQuery(
    val filePaths: List<String>,
)
