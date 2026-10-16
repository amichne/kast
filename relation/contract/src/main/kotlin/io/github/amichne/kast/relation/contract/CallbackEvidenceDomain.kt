package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Workspace read authority permits source proof. A relation destination constrains result eligibility. */
class CallbackEvidenceDomain
private constructor(
    val basis: SemanticReadAuthority,
    val sources: SymbolSearchScope.Workspace,
    val fingerprint: RelationScopeFingerprint,
) {
    val constraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None

    companion object {
        /**
         * Supplier absence includes reverse dependencies, test sources, and generated sources in the imported model.
         */
        fun forRequest(request: RelationRequest): CallbackEvidenceDomain {
            val sources =
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                )
            return CallbackEvidenceDomain(
                request.subject.lease,
                sources,
                RelationScopeFingerprint.from(request.subject, RelationSearchBoundary.Explicit(sources)),
            )
        }
    }
}
