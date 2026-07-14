package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.SymbolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ContainingSymbolEvidence {
    @Serializable
    @SerialName("KNOWN")
    data class Known(val symbol: SymbolIdentity) : ContainingSymbolEvidence

    @Serializable
    @SerialName("TOP_LEVEL")
    data object TopLevel : ContainingSymbolEvidence

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(
        val reason: ContainingSymbolUnavailableReason,
    ) : ContainingSymbolEvidence
}
