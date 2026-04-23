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
 * Selects symbols that best demonstrate kast's value over plain text search.
 *
 * Curation prefers symbols whose simple name is shared by multiple declarations (overloads,
 * same-named classes across packages) because those produce the largest contrast between
 * grep-style search and semantic resolution.
 */
internal class SymbolCurationEngine(
    private val demoCommandSupport: DemoCommandSupport = DemoCommandSupport(),
) {
    fun curate(
        workspaceRoot: Path,
        allSymbols: List<Symbol>,
        count: Int = 3,
    ): List<CuratedSymbol> {
        if (count <= 0 || allSymbols.isEmpty()) {
            return emptyList()
        }

        val groups = allSymbols.groupBy { it.fqName.substringAfterLast('.') }
        val ambiguousGroups = groups.filter { (_, symbols) -> symbols.size >= 2 }
        val singletonGroups = groups.filter { (_, symbols) -> symbols.size == 1 }

        val ambiguousCurated = ambiguousGroups.map { (simpleName, group) ->
            buildCuratedFromGroup(workspaceRoot, simpleName, group)
        }.sortedByDescending { it.contrastScore }

        if (ambiguousCurated.size >= count) {
            return ambiguousCurated.take(count)
        }

        // Fall back to non-ambiguous symbols (groups of size 1) ranked by raw grep noise.
        val singletonCurated = singletonGroups.map { (simpleName, group) ->
            buildCuratedFromGroup(workspaceRoot, simpleName, group)
        }.sortedByDescending { it.grepHits }

        return (ambiguousCurated + singletonCurated).take(count)
    }

    private fun buildCuratedFromGroup(
        workspaceRoot: Path,
        simpleName: String,
        group: List<Symbol>,
    ): CuratedSymbol {
        val summary = demoCommandSupport.analyzeTextSearch(workspaceRoot, group.first())
        val total = summary.totalMatches
        val grepHits = total
        val falsePositiveRatio = if (total == 0) 0.0 else summary.falsePositives.toDouble() / total
        val ambiguityScore = group.size
        // The `+ 1` term keeps the contrast score positive when there is no textual noise but the
        // simple name is still ambiguous across declarations: such symbols still demonstrate
        // semantic value over grep, so they should not collapse to zero.
        val contrastScore = grepHits.toDouble() *
            (summary.falsePositives + summary.ambiguous + 1) /
            maxOf(1, total) *
            ambiguityScore

        // Pick the first symbol in the group as the canonical entry — we want one curated entry
        // per simple name, not one per overload.
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
