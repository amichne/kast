package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
data class KastCallersRequest(
    val workspaceRoot: String? = null,
    val selector: KastExactSymbolSelector,
    val direction: WrapperCallDirection = WrapperCallDirection.INCOMING,
    val depth: Int = 1,
    val maxResults: Int = 4,
    val pageToken: String? = null,
)
