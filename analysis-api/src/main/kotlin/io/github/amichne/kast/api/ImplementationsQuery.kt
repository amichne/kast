package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ImplementationsQuery(
    val position: FilePosition,
    val maxResults: Int = 100,
)
