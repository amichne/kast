package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
data class KastCallersQuery(
    val workspaceRoot: String,
    val selector: KastExactSymbolSelector,
    val direction: WrapperCallDirection,
    val depth: Int,
    val maxResults: Int,
    val pageToken: String? = null,
)
