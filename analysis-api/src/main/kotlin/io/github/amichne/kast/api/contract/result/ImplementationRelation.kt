package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.SymbolIdentity
import kotlinx.serialization.Serializable

@Serializable
data class ImplementationRelation(
    val relation: Kind = Kind.IMPLEMENTATION,
    val implementation: SymbolIdentity,
    val declarationLocation: Location,
) {
    @Serializable
    enum class Kind {
        IMPLEMENTATION,
    }
}
