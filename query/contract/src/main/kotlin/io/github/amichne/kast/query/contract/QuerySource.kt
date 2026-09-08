package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Closed discovery meaning. Enumeration never overloads blank text as a wildcard. */
sealed interface QueryMatch {
    data object All : QueryMatch

    data class Name(
        val pattern: SymbolDiscoveryPattern,
        val policy: SymbolDiscoveryMatch,
    ) : QueryMatch
}

enum class QueryCollectionFailure {
    EMPTY,
    MIXED_LEASE,
}

/** Non-empty declaration kinds requested from one discovery domain. */
class QueryDeclarationKinds private constructor(
    val values: List<CompilerSymbolKind>,
) {
    companion object {
        fun from(
            raw: Set<CompilerSymbolKind>,
        ): Refinement<QueryDeclarationKinds, QueryCollectionFailure> =
            if (raw.isEmpty()) {
                Refinement.Rejected(QueryCollectionFailure.EMPTY)
            } else {
                Refinement.Refined(QueryDeclarationKinds(raw.sortedBy { it.ordinal }))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is QueryDeclarationKinds && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

/** Intersected semantic discovery domain. */
sealed interface QueryScope {
    data object Unrestricted : QueryScope

    data class Restricted(
        val sourceSets: SymbolDiscoverySourceSets,
        val directory: SymbolDiscoveryDirectoryConstraint?,
        val packageName: SymbolDiscoveryPackageConstraint?,
    ) : QueryScope
}

data class QueryDiscoverySyntax(
    val match: QueryMatch,
    val scope: QueryScope,
    val declarationKinds: QueryDeclarationKinds,
)

/** Non-empty declaration candidates from one semantic generation. */
class QueryCandidateReferences private constructor(
    val values: List<SymbolDiscoverySelection>,
) {
    companion object {
        fun from(
            raw: List<SymbolDiscoverySelection>,
        ): Refinement<QueryCandidateReferences, QueryCollectionFailure> {
            if (raw.isEmpty()) return Refinement.Rejected(QueryCollectionFailure.EMPTY)
            val lease = raw.first().lease
            return if (raw.any { it.lease != lease }) {
                Refinement.Rejected(QueryCollectionFailure.MIXED_LEASE)
            } else {
                Refinement.Refined(QueryCandidateReferences(raw.toList()))
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is QueryCandidateReferences && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

/** Non-empty exact references whose generation authority cannot be reconstructed from text. */
class QueryExactReferences private constructor(
    val values: List<SymbolSelector>,
) {
    companion object {
        fun from(
            raw: List<SymbolSelector>,
        ): Refinement<QueryExactReferences, QueryCollectionFailure> {
            if (raw.isEmpty()) return Refinement.Rejected(QueryCollectionFailure.EMPTY)
            val lease = raw.first().lease
            return if (raw.any { it.lease != lease }) {
                Refinement.Rejected(QueryCollectionFailure.MIXED_LEASE)
            } else {
                Refinement.Refined(QueryExactReferences(raw.toList()))
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is QueryExactReferences && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

sealed interface QuerySourceSyntax {
    data class Candidates(val discovery: QueryDiscoverySyntax) : QuerySourceSyntax
    data class Symbols(val discovery: QueryDiscoverySyntax) : QuerySourceSyntax
    data class CandidateReferences(val references: QueryCandidateReferences) : QuerySourceSyntax
    data class ExactReferences(val references: QueryExactReferences) : QuerySourceSyntax
}
