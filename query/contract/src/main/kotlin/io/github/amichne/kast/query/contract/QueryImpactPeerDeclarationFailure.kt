package io.github.amichne.kast.query.contract

/** Exact pairing failure shared by admitted source and original ledger owners. */
internal enum class QueryImpactPeerDeclarationFailure {
    DUPLICATE_BOUNDARY,
    UNDECLARED_BOUNDARY,
    FOREIGN_BASIS,
}
