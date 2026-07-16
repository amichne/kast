package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import kotlinx.serialization.Serializable

@Serializable
data class KastHierarchyRequest(
    val workspaceRoot: String? = null,
    val selector: KastExactSymbolSelector? = null,
    val selectorHandle: String? = null,
    val direction: TypeHierarchyDirection,
    val depth: Int = 1,
    val maxResults: Int = 4,
    val pageToken: String? = null,
)
