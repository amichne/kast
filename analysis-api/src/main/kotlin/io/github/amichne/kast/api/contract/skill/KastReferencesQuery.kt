package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
data class KastReferencesQuery(
    val workspaceRoot: String,
    val symbol: String,
    val fileHint: String? = null,
    val kind: WrapperNamedSymbolKind? = null,
    val containingType: String? = null,
    val includeDeclaration: Boolean = true,
    val maxResults: Int = 10,
    val pageToken: String? = null,
)
