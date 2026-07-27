package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.SymbolIdentity
import kotlinx.serialization.Serializable

@Serializable
data class CallRelation(
    val relation: Kind,
    val relatedSymbol: SymbolIdentity,
    val callSite: Location,
    val depth: Int,
    val containingSymbol: ContainingSymbolEvidence,
) {
    init {
        require(depth > 0) { "Call relation depth must be positive" }
    }

    @Serializable
    enum class Kind {
        CALLER,
        CALLEE,
    }
}
