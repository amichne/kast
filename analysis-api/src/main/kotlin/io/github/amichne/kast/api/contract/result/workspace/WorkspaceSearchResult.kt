@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSearchResult(
    @DocField(description = "Matched lines with absolute file path, line, column, and preview text.")
    val matches: List<SearchMatch>,
    @DocField(description = "True when the result stopped at `maxResults`.", defaultValue = "false")
    val truncated: Boolean,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class SearchMatch(
    @DocField(description = "Absolute path to the file containing the match.")
    val filePath: String,
    @DocField(description = "1-based line number containing the match.")
    val lineNumber: Int,
    @DocField(description = "1-based column number where the match starts.")
    val columnNumber: Int,
    @DocField(description = "Source line preview containing the match.")
    val preview: String,
)
