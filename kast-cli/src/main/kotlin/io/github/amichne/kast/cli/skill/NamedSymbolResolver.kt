package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.WrapperNamedSymbolKind
import io.github.amichne.kast.api.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.CliService
import io.github.amichne.kast.cli.RuntimeCommandOptions

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
            candidates = candidates.filter { it.location.filePath.endsWith(hintSuffix) }
        }

        // Filter by kind
        if (kind != null) {
            val apiKind = kind.toSymbolKind()
            candidates = candidates.filter { it.kind == apiKind }
        }

        // Filter by containing type
        if (!containingType.isNullOrEmpty()) {
            candidates = candidates.filter {
                it.containingDeclaration?.endsWith(containingType) == true
            }
        }

        // Filter to exact name matches (fqName ends with the symbol name)
        val exactMatches = candidates.filter {
            it.fqName.substringAfterLast('.') == symbolName
        }
        if (exactMatches.isNotEmpty()) candidates = exactMatches

        val best = candidates.firstOrNull() ?: return null

        // Confirm via resolve to get enriched symbol info
        val position = FilePosition(
            filePath = best.location.filePath,
            offset = best.location.startOffset,
        )
        val resolveResult = cliService.resolveSymbol(
            options,
            io.github.amichne.kast.api.SymbolQuery(position = position),
        )

        val resolvedSymbol = resolveResult.payload.symbol
        return ResolvedSymbol(
            symbol = resolvedSymbol,
            filePath = resolvedSymbol.location.filePath,
            offset = resolvedSymbol.location.startOffset,
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