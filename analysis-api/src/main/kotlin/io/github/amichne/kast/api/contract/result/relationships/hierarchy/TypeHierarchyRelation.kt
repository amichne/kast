package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.SymbolIdentity
import kotlinx.serialization.Serializable

@Serializable
data class TypeHierarchyRelation(
    val relation: Kind,
    val relatedSymbol: SymbolIdentity,
    val declarationLocation: Location,
    val depth: Int,
) {
    init {
        require(depth > 0) { "Type hierarchy relation depth must be positive" }
    }

    @Serializable
    enum class Kind {
        SUPERTYPE,
        SUBTYPE,
    }
}
