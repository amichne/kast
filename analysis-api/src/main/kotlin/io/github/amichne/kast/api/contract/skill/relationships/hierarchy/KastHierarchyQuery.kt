package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import kotlinx.serialization.Serializable

@Serializable
data class KastHierarchyQuery(
    val workspaceRoot: String,
    val selector: KastExactSymbolSelector,
    val direction: TypeHierarchyDirection,
    val depth: Int,
    val maxResults: Int,
    val pageToken: String? = null,
)
