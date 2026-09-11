package io.github.amichne.kast.source.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.fingerprintFields

/** Scope evidence retained when discovery is refined into source selection. */
sealed interface SourceReadScope {
    /** Historical source-file authority; the snapshot itself retains the exact file. */
    data object ExactFile : SourceReadScope

    data class Constrained(
        val scope: SymbolSearchScope,
        val constraints: SymbolDiscoveryConstraints,
    ) : SourceReadScope
}

fun SourceReadAnchor.readScope(): SourceReadScope =
    when (this) {
        is SourceReadAnchor.Candidate -> SourceReadScope.Constrained(selector.scope, selector.constraints)
        is SourceReadAnchor.Symbol -> SourceReadScope.Constrained(selector.scope, selector.constraints)
        is SourceReadAnchor.Source -> selector.snapshot.readScope
    }

internal fun SourceReadScope.fingerprintFields(): List<String> =
    when (this) {
        SourceReadScope.ExactFile -> emptyList()
        is SourceReadScope.Constrained ->
            buildList {
                val captured = SymbolSearchScope.snapshot(scope)
                add("source-read-scope-v1")
                add(captured.kind.name)
                add(captured.primary ?: "")
                add(captured.secondary ?: "")
                add(captured.sourceKinds.name)
                add(captured.generatedSources.name)
                add(captured.libraries?.name ?: "")
                addAll(constraints.fingerprintFields())
            }
    }
