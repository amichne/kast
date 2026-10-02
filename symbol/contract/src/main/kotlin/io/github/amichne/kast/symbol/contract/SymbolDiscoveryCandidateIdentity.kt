package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Detached declaration/file discovery identity; lexical exemplars explain relevance without creating another owner. */
data class SymbolDiscoveryCandidateIdentity
private constructor(
    val lease: SemanticReadAuthority,
    val kind: SymbolDiscoveryKind,
    val name: SymbolDiscoveryCandidateName,
    val location: SymbolDiscoveryCandidateLocation,
) {
    companion object {
        /** Derives a hashable identity only from an already admitted candidate. */
        internal fun from(candidate: SymbolDiscoveryCandidate): SymbolDiscoveryCandidateIdentity =
            SymbolDiscoveryCandidateIdentity(candidate.lease, candidate.kind, candidate.name, candidate.location)
    }
}
