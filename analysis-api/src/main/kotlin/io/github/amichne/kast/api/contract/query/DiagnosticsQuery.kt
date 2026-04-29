@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsQuery(
    @DocField(description = "Absolute paths of the files to analyze for diagnostics.")
    val filePaths: List<String>,
)
