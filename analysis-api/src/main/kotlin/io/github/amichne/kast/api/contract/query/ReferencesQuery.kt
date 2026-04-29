@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class ReferencesQuery(
    @DocField(description = "File position identifying the symbol whose references to find.")
    val position: FilePosition,
    @DocField(description = "When true, includes the symbol's own declaration in the results.", defaultValue = "false")
    val includeDeclaration: Boolean = false,
)
