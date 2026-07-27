package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ContainingSymbolEvidence {
    @Serializable
    @SerialName("KNOWN")
    data class Known(
        @DocField(description = "Compiler-resolved identity of the containing declaration.")
        val symbol: SymbolIdentity,
    ) : ContainingSymbolEvidence

    @Serializable
    @SerialName("TOP_LEVEL")
    data object TopLevel : ContainingSymbolEvidence

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(
        @DocField(description = "Closed reason semantic owner evidence could not be established.")
        val reason: ContainingSymbolUnavailableReason,
    ) : ContainingSymbolEvidence
}
