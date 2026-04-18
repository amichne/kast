package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CodeActionsQuery(
    val position: FilePosition,
    val diagnosticCode: String? = null,
)
