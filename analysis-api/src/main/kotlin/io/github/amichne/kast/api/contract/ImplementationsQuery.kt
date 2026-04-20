@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class ImplementationsQuery(
    @DocField(description = "File position identifying the interface or abstract class.")
    val position: FilePosition,
    @DocField(description = "Maximum number of implementation symbols to return.", defaultValue = "100")
    val maxResults: Int = 100,
)
