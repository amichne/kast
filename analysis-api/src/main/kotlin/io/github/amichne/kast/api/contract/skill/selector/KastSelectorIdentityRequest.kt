package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.selector.SelectorOperationFamily
import kotlinx.serialization.Serializable

@Serializable
data class KastSelectorIdentityRequest(
    val workspaceRoot: String? = null,
    val selectorHandle: String,
    val family: SelectorOperationFamily,
)
