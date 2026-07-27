@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class SymbolQuery(
    @DocField(description = "File position identifying the symbol to resolve.")
    val position: FilePosition,
    @DocField(description = "When true, populates the declarationScope field on the resolved symbol.", defaultValue = "false")
    val includeDeclarationScope: Boolean = false,
    @DocField(description = "When true, populates the documentation field on the resolved symbol.", defaultValue = "false")
    val includeDocumentation: Boolean = false,
)
