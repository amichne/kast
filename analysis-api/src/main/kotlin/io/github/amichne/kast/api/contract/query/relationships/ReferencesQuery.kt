@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class ReferencesQuery(
    @DocField(description = "File position identifying the symbol whose references to find.")
    val position: FilePosition,
    @DocField(description = "When true, includes the symbol's own declaration in the results.", defaultValue = "false")
    val includeDeclaration: Boolean = false,
    @DocField(
        description = "When true, includes the nearest enclosing declaration scope for each reference usage site.",
        defaultValue = "false",
    )
    val includeUsageSiteScope: Boolean = false,
    @DocField(description = "Maximum number of reference locations to return.", defaultValue = "100")
    val maxResults: Int = 100,
    @DocField(description = "Opaque continuation token from the preceding reference page.")
    val pageToken: String? = null,
    @DocField(description = "Exact declaration identity required by the agent references endpoint.")
    val selector: KastExactSymbolSelector? = null,
)
