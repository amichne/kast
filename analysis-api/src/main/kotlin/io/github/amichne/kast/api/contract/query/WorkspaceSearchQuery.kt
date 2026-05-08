@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSearchQuery(
    @DocField(description = "Search pattern to match as a substring or regex.")
    val pattern: String,
    @DocField(description = "When true, treats the pattern as a regular expression.", defaultValue = "false")
    val regex: Boolean = false,
    @DocField(description = "Maximum number of matches to return.", defaultValue = "100")
    val maxResults: Int = 100,
    @DocField(description = "Optional glob that restricts which files are searched.")
    val fileGlob: String? = null,
    @DocField(description = "When true, matches text with case sensitivity.", defaultValue = "true")
    val caseSensitive: Boolean = true,
)
