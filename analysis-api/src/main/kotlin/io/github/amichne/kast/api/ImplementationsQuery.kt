@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ImplementationsQuery(
    @DocField(description = "File position identifying the interface or abstract class.")
    val position: FilePosition,
    @DocField(description = "Maximum number of implementation symbols to return.")
    val maxResults: Int = 100,
)
