package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
data class KastImplementationsQuery(
    val workspaceRoot: String,
    val selector: KastExactSymbolSelector,
    val maxResults: Int,
    val pageToken: String? = null,
)
