package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.Symbol
import java.nio.file.Path

internal data class CuratedSymbol(
    val symbol: Symbol,
    val simpleName: String,
    val grepHits: Int,
    val falsePositiveRatio: Double,
    val ambiguityScore: Int,
    val contrastScore: Double,
)

/**
 * Returns the minimum contrast score a group must reach to be included in demo output.
 *
 * The bar scales with codebase size: larger codebases have more textual noise, so a higher
 * contrast is needed before a symbol meaningfully demonstrates semantic value over grep.
 * Tiny codebases use a bar of 0.0 so any symbol is acceptable.
 */
internal fun minContrastThreshold(symbolCount: Int): Double = when {
    symbolCount < 50   -> 0.0
    symbolCount < 200  -> 1.0
    symbolCount < 1000 -> 5.0
    symbolCount < 5000 -> 15.0
    else               -> 30.0
}

/**
 * Selects symbols that best demonstrate kast's value over plain text search.
 *
 * Groups are visited in descending group-size order (more overloads = more ambiguous = more
 * interesting) with alphabetical tiebreaking for determinism. For each group, the contrast
 * score is computed on demand; the first [count] groups whose score meets or exceeds the
 * [minContrastThreshold] for this codebase size are returned.
 *
 * This "first-N-above-bar" strategy avoids scoring every group upfront, stops early once
 * [count] are found, and auto-adapts the quality bar to codebase size so large repos only
 * surface high-signal symbols while small repos are still usable.
 */
internal fun interface TextSearchAnalyzer {
    fun analyze(workspaceRoot: Path, symbol: Symbol): DemoTextSearchSummary
}

internal class WorkspaceTextSearchAnalyzer(
    private val indexFactory: (Path) -> WorkspaceTextIndex = ::WorkspaceTextIndex,
) : TextSearchAnalyzer {
    private val indexes = mutableMapOf<Path, WorkspaceTextIndex>()

    override fun analyze(workspaceRoot: Path, symbol: Symbol): DemoTextSearchSummary {
        val root = workspaceRoot.toAbsolutePath().normalize()
        return indexes.getOrPut(root) { indexFactory(root) }.analyze(symbol)
    }
}

internal class SymbolCurationEngine(
    private val textSearchAnalyzer: TextSearchAnalyzer = WorkspaceTextSearchAnalyzer(),
) {
    fun curate(
        workspaceRoot: Path,
        allSymbols: List<Symbol>,
        count: Int = 3,
    ): List<CuratedSymbol> {
        if (count <= 0 || allSymbols.isEmpty()) return emptyList()

        val threshold = minContrastThreshold(allSymbols.size)

        // Larger groups (more overloads) are visited first; alphabetical within same size for
        // determinism.
        val orderedGroups = allSymbols
            .groupBy { it.fqName.substringAfterLast('.') }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Symbol>>> { it.value.size }.thenBy { it.key })

        val accepted = mutableListOf<CuratedSymbol>()
        for ((simpleName, group) in orderedGroups) {
            if (accepted.size >= count) break
            val candidate = buildCuratedFromGroup(workspaceRoot, simpleName, group)
            if (candidate.contrastScore >= threshold) {
                accepted += candidate
            }
        }
        return accepted
    }

    private fun buildCuratedFromGroup(
        workspaceRoot: Path,
        simpleName: String,
        group: List<Symbol>,
    ): CuratedSymbol {
        val summary = textSearchAnalyzer.analyze(workspaceRoot, group.first())
        val total = summary.totalMatches
        val grepHits = total
        val falsePositiveRatio = if (total == 0) 0.0 else summary.falsePositives.toDouble() / total
        val ambiguityScore = group.size
        // The `+ 1` term keeps the contrast score positive when there is no textual noise but the
        // simple name is still ambiguous across declarations.
        val contrastScore = grepHits.toDouble() *
            (summary.falsePositives + summary.ambiguous + 1) /
            maxOf(1, total) *
            ambiguityScore

        return CuratedSymbol(
            symbol = group.first(),
            simpleName = simpleName,
            grepHits = grepHits,
            falsePositiveRatio = falsePositiveRatio,
            ambiguityScore = ambiguityScore,
            contrastScore = contrastScore,
        )
    }
}
