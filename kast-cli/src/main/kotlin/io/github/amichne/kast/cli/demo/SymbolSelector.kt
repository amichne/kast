package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.ReferencesResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.cli.DemoTextSearchSummary

/**
 * Heuristic configuration for [SymbolSelector]. Defaults match the values
 * documented in `kast-demo-spec.md` for an out-of-the-box demo run.
 */
internal data class DemoSelectionConfig(
    val minRefs: Int = 5,
    val noiseRatio: Double = 2.0,
    val maxCandidates: Int = 20,
) {
    init {
        require(minRefs >= 0) { "minRefs must be >= 0 (was $minRefs)" }
        require(noiseRatio >= 0.0) { "noiseRatio must be >= 0 (was $noiseRatio)" }
        require(maxCandidates >= 1) { "maxCandidates must be >= 1 (was $maxCandidates)" }
    }
}

/**
 * Combined evidence about a candidate symbol. The selector uses these fields
 * to decide qualification; downstream demo acts reuse them so we only run
 * the expensive text-search/reference probes once per chosen symbol.
 */
internal data class SymbolEvidence(
    val textSearch: DemoTextSearchSummary,
    val references: ReferencesResult,
) {
    val resolvedRefCount: Int get() = references.references.size
    val grepHitCount: Int get() = textSearch.totalMatches
    val distinctFileCount: Int get() = references.references.asSequence().map { it.filePath }.toSet().size
}

/**
 * Pure abstraction for fetching evidence about a symbol. Production code
 * combines a grep-style text-search analyzer with `findReferences`; tests
 * can supply canned evidence without spinning up a backend.
 *
 * Implementations are free to throw — the selector treats infrastructure
 * failures as fatal and lets them propagate so the CLI can map them onto
 * the appropriate exit code.
 */
internal fun interface SymbolProbe {
    suspend fun probe(symbol: Symbol): SymbolEvidence
}

/**
 * Outcome of a [SymbolSelector.select] call. Represents only the two
 * non-fatal results: we found a qualifying symbol, or we exhausted the
 * inspection budget without one. Infrastructure failures (backend down,
 * index unavailable, etc.) are surfaced via thrown exceptions on the probe.
 */
internal sealed interface SelectionOutcome {
    data class Found(
        val symbol: Symbol,
        val evidence: SymbolEvidence,
    ) : SelectionOutcome

    data class NoQualifyingSymbol(
        val reason: String,
        val candidatesInspected: Int,
    ) : SelectionOutcome
}

/**
 * Picks the first symbol from a workspace listing that satisfies the demo's
 * "this is interesting to compare against grep" thresholds:
 *   * refs >= [DemoSelectionConfig.minRefs]
 *   * grepHits / refs >= [DemoSelectionConfig.noiseRatio]
 *   * references span at least two distinct files
 *
 * Candidates are pre-sorted to favour names that grep historically struggles
 * with (short, generic identifiers like `name`, `id`, `execute`). Private and
 * local declarations are dropped up front because they cannot satisfy the
 * cross-file requirement and would burn the inspection budget.
 */
internal class SymbolSelector(private val config: DemoSelectionConfig = DemoSelectionConfig()) {

    suspend fun select(
        candidates: List<Symbol>,
        probe: SymbolProbe,
    ): SelectionOutcome {
        val pool = candidates
            .asSequence()
            .filter { it.visibility != SymbolVisibility.PRIVATE && it.visibility != SymbolVisibility.LOCAL }
            .filter { it.fqName.substringAfterLast('.').length in 1..MAX_NAME_LENGTH }
            .sortedWith(SCORE_COMPARATOR)
            .take(config.maxCandidates)
            .toList()

        if (pool.isEmpty()) {
            return SelectionOutcome.NoQualifyingSymbol(
                reason = "no public symbols available to inspect",
                candidatesInspected = 0,
            )
        }

        var inspected = 0
        for (symbol in pool) {
            inspected++
            val evidence = probe.probe(symbol)
            if (qualifies(evidence)) {
                return SelectionOutcome.Found(symbol, evidence)
            }
        }
        return SelectionOutcome.NoQualifyingSymbol(
            reason = "no symbol cleared the demo thresholds " +
                "(min-refs=${config.minRefs}, noise-ratio=${config.noiseRatio})",
            candidatesInspected = inspected,
        )
    }

    private fun qualifies(evidence: SymbolEvidence): Boolean {
        val refs = evidence.resolvedRefCount
        if (refs < config.minRefs) return false
        if (evidence.distinctFileCount < MIN_DISTINCT_FILES) return false
        val grep = evidence.grepHitCount
        if (grep <= 0) return false
        val ratio = grep.toDouble() / refs.toDouble()
        return ratio >= config.noiseRatio
    }

    internal companion object {
        const val MAX_NAME_LENGTH: Int = 20
        const val MIN_DISTINCT_FILES: Int = 2

        /**
         * Simple names that grep famously over-matches in any non-trivial Kotlin
         * codebase. Pre-loading them gives the selector a head start before it
         * ever runs a probe.
         */
        val NOISY_NAMES: Set<String> = setOf(
            "id", "name", "type", "execute", "handle", "build", "create",
            "get", "set", "run", "map", "parse", "load", "init", "update",
            "apply", "invoke", "emit", "value", "key",
        )

        private val SCORE_COMPARATOR: Comparator<Symbol> =
            compareByDescending<Symbol> { score(it) }.thenBy { it.fqName }

        private fun score(symbol: Symbol): Int {
            val simple = symbol.fqName.substringAfterLast('.').lowercase()
            var total = 0
            if (simple in NOISY_NAMES) total += 100
            // Shorter simple names are noisier in raw grep output.
            total += (MAX_NAME_LENGTH - simple.length).coerceAtLeast(0)
            return total
        }
    }
}
