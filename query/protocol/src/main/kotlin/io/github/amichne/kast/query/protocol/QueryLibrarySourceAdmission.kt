package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.query.contract.QueryDeclarationKinds
import io.github.amichne.kast.query.contract.QueryDiscoverySyntax
import io.github.amichne.kast.query.contract.QueryMatch
import io.github.amichne.kast.query.contract.QueryScope
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern

/** An exact name and package are the only admitted bounds of a dependency search. */
internal fun QueryFromDocument.Library.syntax(): QueryDiscoverySyntax? {
    val pattern = SymbolDiscoveryPattern.parse(name.value) as? Refinement.Refined ?: return null
    val owner = SymbolDiscoveryPackage.parse(packageName.value) as? Refinement.Refined ?: return null
    val families = CompilerSymbolKind.entries.toSet() - CompilerSymbolKind.CONSTRUCTOR
    val kinds = QueryDeclarationKinds.from(families) as? Refinement.Refined ?: return null
    return QueryDiscoverySyntax(
        QueryMatch.Name(pattern.value, SymbolDiscoveryMatch.EXACT_NAME),
        QueryScope.Library(SymbolDiscoveryPackageConstraint(owner.value, SymbolDiscoveryContainment.DIRECT)),
        kinds.value,
    )
}
