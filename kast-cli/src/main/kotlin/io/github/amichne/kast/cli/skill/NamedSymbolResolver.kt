package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.wrapper.WrapperNamedSymbolKind
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.tty.CliService
import io.github.amichne.kast.cli.options.RuntimeCommandOptions

/**
 * Resolves a named symbol (e.g. "MyClass", "doStuff") to a [FilePosition] + [Symbol]
 * by performing a workspace symbol search, filtering candidates, and confirming
 * via symbol/resolve.
 *
 * This is the Kotlin port of the former shell helper that resolved named symbol queries.
 */
internal class NamedSymbolResolver(
    private val cliService: CliService,
) {

    data class ResolvedSymbol(
        val symbol: Symbol,
        val filePath: String,
        val offset: Int,
        val candidateCount: Int = 1,
        val alternativeFqNames: List<String> = emptyList(),
    )

    /**
     * Resolves a named symbol to a file position.
     *
     * The algorithm:
     * 1. Workspace symbol search for the pattern
     * 2. Filter by [fileHint] (path suffix match) if provided
     * 3. Filter by [kind] if provided
     * 4. Filter by [containingType] if provided
     * 5. Pick the first remaining candidate
     * 6. Resolve by offset to confirm and get full symbol info
     *
     * @return the resolved symbol, or null if no matching symbol was found
     */
    suspend fun resolve(
        options: RuntimeCommandOptions,
        symbolName: String,
        fileHint: String? = null,
        kind: WrapperNamedSymbolKind? = null,
        containingType: String? = null,
    ): ResolvedSymbol? {
        val searchResult = cliService.workspaceSymbolSearch(
            options,
            WorkspaceSymbolQuery(pattern = symbolName, maxResults = 100),
        )
        var candidates = searchResult.payload.symbols

        // Filter by file hint (path suffix match)
        if (!fileHint.isNullOrEmpty()) {
            val hintSuffix = fileHint.removePrefix("/")
            val filtered = candidates.filter { it.location.filePath.endsWith(hintSuffix) }
            if (filtered.isNotEmpty()) candidates = filtered
        }

        // Filter by kind
        if (kind != null) {
            val apiKind = kind.toSymbolKind()
            val filtered = candidates.filter { it.kind == apiKind }
            if (filtered.isNotEmpty()) candidates = filtered
        }

        // Filter by containing type
        if (!containingType.isNullOrEmpty()) {
            val filtered = candidates.filter {
                it.containingDeclaration?.endsWith(containingType) == true
            }
            if (filtered.isNotEmpty()) candidates = filtered
        }

        // Filter to exact name matches (fqName ends with the symbol name)
        val exactMatches = candidates.filter {
            it.fqName.substringAfterLast('.') == symbolName
        }
        if (exactMatches.isNotEmpty()) candidates = exactMatches

        val candidateCount = candidates.size
        val best = candidates.firstOrNull() ?: return null
        val alternativeFqNames = candidates
            .asSequence()
            .map(Symbol::fqName)
            .filter { it != best.fqName }
            .distinct()
            .take(3)
            .toList()

        // Confirm via resolve to get enriched symbol info
        val position = FilePosition(
            filePath = best.location.filePath,
            offset = best.location.startOffset,
        )
        val resolveResult = cliService.resolveSymbol(
            options,
            SymbolQuery(position = position),
        )

        return ResolvedSymbol(
            symbol = resolveResult.payload.symbol,
            filePath = best.location.filePath,
            offset = best.location.startOffset,
            candidateCount = candidateCount,
            alternativeFqNames = alternativeFqNames,
        )
    }
}

private fun WrapperNamedSymbolKind.toSymbolKind(): SymbolKind = when (this) {
    WrapperNamedSymbolKind.CLASS -> SymbolKind.CLASS
    WrapperNamedSymbolKind.INTERFACE -> SymbolKind.INTERFACE
    WrapperNamedSymbolKind.OBJECT -> SymbolKind.OBJECT
    WrapperNamedSymbolKind.FUNCTION -> SymbolKind.FUNCTION
    WrapperNamedSymbolKind.PROPERTY -> SymbolKind.PROPERTY
}
