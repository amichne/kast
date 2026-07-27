@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class CodeActionsQuery(
    @DocField(description = "File position to query for available code actions.")
    val position: FilePosition,
    @DocField(description = "Filter to actions that address this diagnostic code.")
    val diagnosticCode: String? = null,
)
