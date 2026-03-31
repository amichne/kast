package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ReferencesQuery(
    val position: FilePosition,
    val includeDeclaration: Boolean = false,
)
