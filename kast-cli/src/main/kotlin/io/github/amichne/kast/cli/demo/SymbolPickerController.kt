package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind

/**
 * A row in the picker results list with pre-computed display fields.
 */
internal data class SymbolPickerDisplayRow(
    val symbol: Symbol,
    val displayName: String,
    val kindLabel: String,
    val contextHint: String,
)

/**
 * Immutable snapshot of the symbol picker state.
 */
internal data class SymbolPickerState(
    val searchText: String = "",
    val selectedIndex: Int = 0,
    val kindFilters: Map<SymbolKind, Boolean>,
    val focusedKindIndex: Int = 0,
    val filteredResults: List<Symbol> = emptyList(),
    val displayRows: List<SymbolPickerDisplayRow> = emptyList(),
    val needsMoreChars: Boolean = true,
    val phase: SymbolPickerPhase = SymbolPickerPhase.WARMING,
)

internal enum class SymbolPickerPhase {
    WARMING,
    READY,
    HAS_RESULTS,
}

/**
 * Pure state machine for the interactive symbol picker.
 *
 * Handles search text editing, kind filter toggling, result navigation,
 * and display name resolution. No Kotter dependency — testable in isolation.
 */
internal class SymbolPickerController(
    private val verbose: Boolean = false,
    val minSearchChars: Int = MIN_SEARCH_CHARS_DEFAULT,
) {
    private var allSymbols: List<Symbol> = emptyList()
    private var _searchText: String = ""
    private var _selectedIndex: Int = 0
    private var _kindFilters: MutableMap<SymbolKind, Boolean> = FILTERABLE_KINDS.associateWith { true }.toMutableMap()
    private var _focusedKindIndex: Int = 0
    private var _phase: SymbolPickerPhase = SymbolPickerPhase.WARMING

    val state: SymbolPickerState
        get() {
            val filtered = filterSymbols()
            val rows = buildDisplayRows(filtered)
            return SymbolPickerState(
                searchText = _searchText,
                selectedIndex = _selectedIndex,
                kindFilters = _kindFilters.toMap(),
                focusedKindIndex = _focusedKindIndex,
                filteredResults = filtered,
                displayRows = rows,
                needsMoreChars = _searchText.length < minSearchChars,
                phase = _phase,
            )
        }

    fun onSymbolsLoaded(symbols: List<Symbol>) {
        allSymbols = symbols
        _phase = SymbolPickerPhase.READY
        refilter()
    }

    fun onChar(ch: Char) {
        _searchText += ch
        _selectedIndex = 0
        refilter()
    }

    fun onBackspace() {
        if (_searchText.isNotEmpty()) {
            _searchText = _searchText.dropLast(1)
            _selectedIndex = 0
            refilter()
        }
    }

    fun onUp() {
        _selectedIndex = (_selectedIndex - 1).coerceAtLeast(0)
    }

    fun onDown() {
        val maxIndex = filterSymbols().size - 1
        _selectedIndex = (_selectedIndex + 1).coerceAtMost(maxIndex.coerceAtLeast(0))
    }

    fun onTab() {
        _focusedKindIndex = (_focusedKindIndex + 1) % FILTERABLE_KINDS.size
    }

    fun onSpace() {
        val kind = FILTERABLE_KINDS[_focusedKindIndex]
        _kindFilters[kind] = !_kindFilters.getValue(kind)
        _selectedIndex = 0
        refilter()
    }

    fun onEnter(): Symbol? {
        val results = filterSymbols()
        return results.getOrNull(_selectedIndex)
    }

    fun onEsc(): Symbol? = null

    // ── private ─────────────────────────────────────────────────────

    private fun refilter() {
        val results = filterSymbols()
        if (_selectedIndex >= results.size) {
            _selectedIndex = (results.size - 1).coerceAtLeast(0)
        }
        if (results.isNotEmpty()) {
            _phase = SymbolPickerPhase.HAS_RESULTS
        }
    }

    private fun filterSymbols(): List<Symbol> {
        if (_searchText.length < minSearchChars) return emptyList()

        val enabledKinds = _kindFilters.filterValues { it }.keys
        return allSymbols
            .filter { it.kind in enabledKinds }
            .filter { !isExcludedPath(it.location.filePath) }
            .filter { matchesSearch(it, _searchText) }
    }

    private fun buildDisplayRows(symbols: List<Symbol>): List<SymbolPickerDisplayRow> {
        if (verbose) {
            return symbols.map { symbol ->
                SymbolPickerDisplayRow(
                    symbol = symbol,
                    displayName = symbol.fqName,
                    kindLabel = symbol.kind.label(),
                    contextHint = "",
                )
            }
        }

        val simpleNameCounts = symbols.groupingBy { it.fqName.simpleName() }.eachCount()

        return symbols.map { symbol ->
            val simpleName = symbol.fqName.simpleName()
            val hasCollision = (simpleNameCounts[simpleName] ?: 0) > 1
            val displayName = if (hasCollision) {
                symbol.fqName.disambiguatedName()
            } else {
                simpleName
            }
            SymbolPickerDisplayRow(
                symbol = symbol,
                displayName = displayName,
                kindLabel = symbol.kind.label(),
                contextHint = symbol.fqName.contextHint(),
            )
        }
    }

    companion object {
        const val MIN_SEARCH_CHARS_DEFAULT = 3
        const val MAX_VISIBLE_RESULTS = 15

        val FILTERABLE_KINDS: List<SymbolKind> = listOf(
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.OBJECT,
            SymbolKind.FUNCTION,
            SymbolKind.PROPERTY,
        )

        internal fun isExcludedPath(filePath: String): Boolean =
            "/build/" in filePath ||
                "/buildSrc/build/" in filePath ||
                "/.gradle/" in filePath

        private fun matchesSearch(symbol: Symbol, query: String): Boolean {
            val simpleName = symbol.fqName.simpleName()
            return simpleName.contains(query)
        }

        private fun String.simpleName(): String = substringAfterLast('.')

        private fun String.disambiguatedName(): String {
            val parts = split('.')
            return if (parts.size >= 2) {
                "${parts[parts.size - 2]}.${parts.last()}"
            } else {
                this
            }
        }

        private fun String.contextHint(): String {
            val parts = split('.')
            return if (parts.size >= 2) parts[parts.size - 2] else ""
        }

        private fun SymbolKind.label(): String = name.lowercase()
    }
}
