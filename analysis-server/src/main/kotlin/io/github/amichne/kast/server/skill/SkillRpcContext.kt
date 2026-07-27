package io.github.amichne.kast.server.skill

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.server.AnalysisServerConfig

internal class SkillRpcContext(
    val backend: AnalysisBackend,
    val config: AnalysisServerConfig,
)

internal const val DEFAULT_DISCOVERY_SEARCH_LIMIT = 100
internal const val EXACT_CARDINALITY_LIMIT = 2
internal const val EXACT_CONSTRAINED_SEARCH_LIMIT = Int.MAX_VALUE
internal const val MAX_SURROUNDING_LINES = 50

internal data class ExactNamedSymbolCandidate(
    val ranked: RankedNamedSymbolCandidate,
    val resolvedConstraintSymbol: Symbol?,
)

internal sealed interface SelectorSelection {
    sealed interface Selected : SelectorSelection {
        val selector: KastExactSymbolSelector
    }

    data class Explicit(
        override val selector: KastExactSymbolSelector,
    ) : Selected

    data class Handle(
        override val selector: KastExactSymbolSelector,
    ) : Selected

    data class Rejected(
        val reason: SelectorHandleAuthority.Resolution.RejectionReason,
    ) : SelectorSelection
}

internal data class ResolvedNamedSymbol(
    val symbol: Symbol,
    val filePath: String,
    val offset: Int,
    val candidateCount: Int,
    val alternativeFqNames: List<String>,
)

internal data class RankedNamedSymbolCandidate(
    val symbol: Symbol,
    val score: Int,
    val reasons: List<String>,
)

internal fun placeholderLogFile(): String = "/dev/null"
