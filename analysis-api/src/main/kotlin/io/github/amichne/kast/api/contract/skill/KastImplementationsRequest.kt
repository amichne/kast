package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
data class KastImplementationsRequest(
    val workspaceRoot: String? = null,
    val selector: KastExactSymbolSelector,
    val maxResults: Int = 4,
    val pageToken: String? = null,
)
