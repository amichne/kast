package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.util.Collections

/** Exact imported Gradle source-set identities, independent of production/test classification. */
sealed interface SymbolDiscoverySourceSets {
    data object All : SymbolDiscoverySourceSets

    class Exact private constructor(val values: Set<WorkspaceSourceSetName>) : SymbolDiscoverySourceSets {
        companion object {
            /** Refines a name set to a non-empty selection; raw names stay in model adapters. */
            fun from(raw: Set<WorkspaceSourceSetName>): Refinement<Exact, SymbolDiscoverySourceSetsFailure> =
                if (raw.isEmpty()) {
                    Refinement.Rejected(SymbolDiscoverySourceSetsFailure.EMPTY)
                } else {
                    Refinement.Refined(Exact(Collections.unmodifiableSet(raw.sortedBy { it.value }.toSet())))
                }
        }

        override fun equals(other: Any?): Boolean = other is Exact && values == other.values

        override fun hashCode(): Int = values.hashCode()
    }
}

enum class SymbolDiscoverySourceSetsFailure {
    EMPTY
}
