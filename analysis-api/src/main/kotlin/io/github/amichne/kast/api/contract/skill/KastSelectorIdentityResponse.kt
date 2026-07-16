package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.SymbolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastSelectorIdentityResponse

@Serializable
@SerialName("AVAILABLE")
data class KastSelectorIdentityAvailableResponse(
    val identity: SymbolIdentity,
) : KastSelectorIdentityResponse
