package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget

private const val MAX_DISCOVERY_PATTERN_LENGTH = 256

enum class SymbolDiscoveryKind {
    FILE,
    CLASS,
    SYMBOL,
    TEXT,
}

enum class SymbolNameDiscoveryKind {
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
         * Establishes a non-blank, bounded IntelliJ discovery pattern without control characters.
         * [SymbolDiscoveryPatternFailure] is the closed expected failure. Raw text may be extracted
         * only by the request-local native matcher, provider, or indexed-text boundary.
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

/** Closed domain meaning for the existing `symbol.discover` operation. */
sealed interface SymbolDiscoveryTarget {
    /** Finite result-kind admission owned by the semantic target. */
    fun admits(candidate: SymbolDiscoveryKind): Boolean

    data class Name(
        val kind: SymbolNameDiscoveryKind,
        val pattern: SymbolDiscoveryPattern,
        val match: SymbolDiscoveryMatch,
    ) : SymbolDiscoveryTarget {
        val resultKind: SymbolDiscoveryKind = when (kind) {
            SymbolNameDiscoveryKind.FILE -> SymbolDiscoveryKind.FILE
            SymbolNameDiscoveryKind.CLASS -> SymbolDiscoveryKind.CLASS
            SymbolNameDiscoveryKind.SYMBOL -> SymbolDiscoveryKind.SYMBOL
        }

        override fun admits(candidate: SymbolDiscoveryKind): Boolean = candidate == resultKind
    }

    data class Location(
        val file: CanonicalWorkspaceFilePath,
        val offset: SymbolDiscoverySourceOffset,
    ) : SymbolDiscoveryTarget {
        override fun admits(candidate: SymbolDiscoveryKind): Boolean = candidate.isDeclaration()
    }

    data class Text(
        val pattern: SymbolDiscoveryPattern,
    ) : SymbolDiscoveryTarget {
        override fun admits(candidate: SymbolDiscoveryKind): Boolean =
            candidate == SymbolDiscoveryKind.TEXT
    }
}

data class SymbolDiscoveryRequest(
    val scope: SymbolSearchScopeRequest,
    val target: SymbolDiscoveryTarget,
    val budget: SymbolDiscoveryBudget,
)

private fun SymbolDiscoveryKind.isDeclaration(): Boolean = when (this) {
    SymbolDiscoveryKind.CLASS,
    SymbolDiscoveryKind.SYMBOL,
        -> true
    SymbolDiscoveryKind.FILE,
    SymbolDiscoveryKind.TEXT,
        -> false
}
