@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class ImplementationsQuery(
    @DocField(description = "File position identifying the interface or abstract class.")
    val position: FilePosition,
    @DocField(description = "Maximum number of implementation symbols to return.", defaultValue = "100")
    val maxResults: Int = 100,
)
