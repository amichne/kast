package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.cli.CliTextTheme
import java.nio.file.Path

/**
 * Formats [Symbol]s and [Location]s for the demo presentation layer.
 *
 * Default (non-verbose) rendering collapses to the simple name + bare file
 * name that fits a normal terminal. [verbose] mode re-expands to the fully-
 * qualified name and workspace-relative path — handy when the operator is
 * reviewing the walker transcript later and needs unambiguous anchors.
 *
 * Display is deliberately kept separate from [CliTextTheme] so tests can
 * assert on plain-text output while the theme owns the ANSI styling.
 */
internal class SymbolDisplay(
    private val workspaceRoot: Path,
    val verbose: Boolean,
) {
    /** How the symbol is named in the walker UI. */
    fun name(symbol: Symbol): String = if (verbose) symbol.fqName else simpleName(symbol)

    /** Always the simple / short name, regardless of verbose. */
    fun simpleName(symbol: Symbol): String = symbol.fqName.substringAfterLast('.')

    /** How a location is rendered in walker rows (`file:line` by default, workspace-relative when verbose). */
    fun locationLabel(location: Location): String = Paths.locationLine(workspaceRoot, location, verbose)

    /** Human-readable kind label, e.g. `class`, `function`, `property`. */
    fun kindLabel(kind: SymbolKind): String = when (kind) {
        SymbolKind.CLASS -> "class"
        SymbolKind.INTERFACE -> "interface"
        SymbolKind.OBJECT -> "object"
        SymbolKind.FUNCTION -> "function"
        SymbolKind.PROPERTY -> "property"
        SymbolKind.PARAMETER -> "parameter"
        SymbolKind.UNKNOWN -> "symbol"
    }

    /** The header used when previewing a location in its own panel. */
    fun fileHeaderLabel(location: Location): String =
        if (verbose) Paths.relative(workspaceRoot, location.filePath) else Paths.fileName(location.filePath)
}
