package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ImportOptimizeQuery(
    val filePaths: List<String>,
)
