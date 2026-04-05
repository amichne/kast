package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class RefreshQuery(
    val filePaths: List<String> = emptyList(),
)
