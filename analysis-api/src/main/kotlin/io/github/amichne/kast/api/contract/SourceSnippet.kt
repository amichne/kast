@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class SourceSnippet(
    @DocField(description = "One-based line number where the snippet starts.")
    val startLine: Int,
    @DocField(description = "One-based line number where the snippet ends.")
    val endLine: Int,
    @DocField(description = "One-based line number containing the requested symbol within the snippet.")
    val focusLine: Int,
    @DocField(description = "Snippet source text.")
    val sourceText: String,
    @DocField(description = "True when the server truncated the requested context to stay within response bounds.")
    val truncated: Boolean = false,
)
