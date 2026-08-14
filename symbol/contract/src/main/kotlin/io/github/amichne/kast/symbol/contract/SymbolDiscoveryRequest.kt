package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget

private const val MAX_DISCOVERY_PATTERN_LENGTH = 256

enum class SymbolDiscoveryKind {
    FILE,
    CLASS,
    SYMBOL,
}

enum class SymbolDiscoveryMatch {
    FUZZY,
    EXACT_NAME,
}

enum class SymbolDiscoveryPatternFailure {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
}

@JvmInline
value class SymbolDiscoveryPattern private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition:
         * String to Refinement<SymbolDiscoveryPattern, SymbolDiscoveryPatternFailure>.
         *
         * Establishes a non-blank, bounded IntelliJ Choose-by-Name pattern without control
         * characters. [SymbolDiscoveryPatternFailure] is the closed expected failure. Raw text may
         * be extracted only by the request-local native matcher and provider boundary.
         */
        fun parse(raw: String): Refinement<SymbolDiscoveryPattern, SymbolDiscoveryPatternFailure> = when {
            raw.isBlank() -> Refinement.Rejected(SymbolDiscoveryPatternFailure.BLANK)
            raw.length > MAX_DISCOVERY_PATTERN_LENGTH ->
                Refinement.Rejected(SymbolDiscoveryPatternFailure.TOO_LONG)
            raw.any(Char::isISOControl) ->
                Refinement.Rejected(SymbolDiscoveryPatternFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(SymbolDiscoveryPattern(raw))
        }
    }
}

enum class SymbolDiscoveryByteLimitFailure {
    NOT_POSITIVE,
}

@JvmInline
value class SymbolDiscoveryByteLimit private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<SymbolDiscoveryByteLimit, SymbolDiscoveryByteLimitFailure>.
         *
         * Establishes a finite, strictly positive byte bound for the canonical detached candidate
         * projection. [SymbolDiscoveryByteLimitFailure] is the closed expected failure. Raw bytes
         * may be extracted only by the request-local bounded projection collector.
         */
        fun parse(raw: Long): Refinement<SymbolDiscoveryByteLimit, SymbolDiscoveryByteLimitFailure> =
            if (raw > 0L) {
                Refinement.Refined(SymbolDiscoveryByteLimit(raw))
            } else {
                Refinement.Rejected(SymbolDiscoveryByteLimitFailure.NOT_POSITIVE)
            }
    }
}

data class SymbolDiscoveryBudget(
    val resources: ResourceBudget,
    val returnedBytes: SymbolDiscoveryByteLimit,
)

data class SymbolDiscoveryRequest(
    val scope: SymbolSearchScopeRequest,
    val kind: SymbolDiscoveryKind,
    val pattern: SymbolDiscoveryPattern,
    val budget: SymbolDiscoveryBudget,
    val match: SymbolDiscoveryMatch = SymbolDiscoveryMatch.FUZZY,
)
