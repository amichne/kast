package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
data class KastReferencesQuery(
    val workspaceRoot: String,
    val selector: KastExactSymbolSelector,
    val includeDeclaration: Boolean = true,
    val includeUsageSiteScope: Boolean = false,
    val maxResults: Int = 10,
    val pageToken: String? = null,
)
