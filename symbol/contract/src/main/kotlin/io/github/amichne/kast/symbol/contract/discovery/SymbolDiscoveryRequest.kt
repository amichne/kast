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
    ) : ConstrainedSymbolDiscoveryTarget {
        val resultKind: SymbolDiscoveryKind = when (kind) {
            SymbolNameDiscoveryKind.FILE -> SymbolDiscoveryKind.FILE
            SymbolNameDiscoveryKind.CLASS -> SymbolDiscoveryKind.CLASS
            SymbolNameDiscoveryKind.SYMBOL -> SymbolDiscoveryKind.SYMBOL
        }

        override fun admits(candidate: SymbolDiscoveryKind): Boolean = candidate == resultKind
    }

    /** Explicit scoped enumeration; unlike [Name], it carries no text query. */
    data class All(
        val kind: SymbolNameDiscoveryKind,
    ) : ConstrainedSymbolDiscoveryTarget {
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
    ) : SupplementalSymbolDiscoveryTarget {
        override fun admits(candidate: SymbolDiscoveryKind): Boolean = candidate.isDeclaration()
    }

    data class Text(
        val pattern: SymbolDiscoveryPattern,
    ) : SupplementalSymbolDiscoveryTarget {
        override fun admits(candidate: SymbolDiscoveryKind): Boolean =
            candidate == SymbolDiscoveryKind.TEXT
    }
}

/** Indexed targets whose adapter executes semantic discovery constraints before budget use. */
sealed interface ConstrainedSymbolDiscoveryTarget : SymbolDiscoveryTarget

/** Supplemental targets whose exact file/text scope is fully expressed by the target itself. */
sealed interface SupplementalSymbolDiscoveryTarget : SymbolDiscoveryTarget

enum class SymbolDiscoveryContainment {
    DIRECT,
    DESCENDANTS,
}

enum class SymbolDiscoveryDirectoryFailure {
    BLANK,
    ABSOLUTE,
    CONTROL_CHARACTER,
    NON_CANONICAL,
}

/** Canonical workspace-relative directory restriction evaluated before candidate budget use. */
@JvmInline
value class SymbolDiscoveryDirectory private constructor(
    val value: String,
) {
    companion object {
        fun parse(
            raw: String,
        ): Refinement<SymbolDiscoveryDirectory, SymbolDiscoveryDirectoryFailure> = when {
            raw.isBlank() -> Refinement.Rejected(SymbolDiscoveryDirectoryFailure.BLANK)
            raw.startsWith('/') -> Refinement.Rejected(SymbolDiscoveryDirectoryFailure.ABSOLUTE)
            raw.any(Char::isISOControl) ->
                Refinement.Rejected(SymbolDiscoveryDirectoryFailure.CONTROL_CHARACTER)
            raw != "." && raw.split('/').any { it.isBlank() || it == "." || it == ".." } ->
                Refinement.Rejected(SymbolDiscoveryDirectoryFailure.NON_CANONICAL)
            else -> Refinement.Refined(SymbolDiscoveryDirectory(raw))
        }
    }
}

enum class SymbolDiscoveryPackageFailure {
    BLANK,
    INVALID_SEGMENT,
}

/** Canonical dotted package restriction established from declaration PSI, not file spelling. */
@JvmInline
value class SymbolDiscoveryPackage private constructor(
    val value: String,
) {
    companion object {
        private val segment = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun parse(
            raw: String,
        ): Refinement<SymbolDiscoveryPackage, SymbolDiscoveryPackageFailure> = when {
            raw.isBlank() -> Refinement.Rejected(SymbolDiscoveryPackageFailure.BLANK)
            raw.split('.').any { !segment.matches(it) } ->
                Refinement.Rejected(SymbolDiscoveryPackageFailure.INVALID_SEGMENT)
            else -> Refinement.Refined(SymbolDiscoveryPackage(raw))
        }
    }
}

data class SymbolDiscoveryDirectoryConstraint(
    val directory: SymbolDiscoveryDirectory,
    val containment: SymbolDiscoveryContainment,
)

data class SymbolDiscoveryPackageConstraint(
    val packageName: SymbolDiscoveryPackage,
    val containment: SymbolDiscoveryContainment,
)

/** Non-empty compiler declaration-kind restriction applied before candidate work accounting. */
class SymbolDiscoveryDeclarationKinds private constructor(
    val values: Set<CompilerSymbolKind>,
) {
    companion object {
        fun from(
            raw: Set<CompilerSymbolKind>,
        ): Refinement<SymbolDiscoveryDeclarationKinds, SymbolDiscoveryDeclarationKindsFailure> =
            if (raw.isEmpty()) {
                Refinement.Rejected(SymbolDiscoveryDeclarationKindsFailure.EMPTY)
            } else {
                Refinement.Refined(SymbolDiscoveryDeclarationKinds(raw.toSet()))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is SymbolDiscoveryDeclarationKinds && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

enum class SymbolDiscoveryDeclarationKindsFailure { EMPTY }

/** Intersected discovery restrictions applied inside the compiled IntelliJ scope. */
data class SymbolDiscoveryConstraints(
    val directory: SymbolDiscoveryDirectoryConstraint?,
    val packageName: SymbolDiscoveryPackageConstraint?,
    val declarationKinds: SymbolDiscoveryDeclarationKinds? = null,
    val sourceSets: SymbolDiscoverySourceSets = SymbolDiscoverySourceSets.All,
) {
    companion object {
        val None: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints(null, null, null)
    }
}

class SymbolDiscoveryRequest private constructor(
    val scope: SymbolSearchScopeRequest,
    val target: SymbolDiscoveryTarget,
    val budget: SymbolDiscoveryBudget,
    val constraints: SymbolDiscoveryConstraints,
) {
    companion object {
        operator fun invoke(
            scope: SymbolSearchScopeRequest,
            target: ConstrainedSymbolDiscoveryTarget,
            budget: SymbolDiscoveryBudget,
            constraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        ): SymbolDiscoveryRequest = SymbolDiscoveryRequest(scope, target, budget, constraints)

        operator fun invoke(
            scope: SymbolSearchScopeRequest,
            target: SupplementalSymbolDiscoveryTarget,
            budget: SymbolDiscoveryBudget,
        ): SymbolDiscoveryRequest = SymbolDiscoveryRequest(
            scope,
            target,
            budget,
            SymbolDiscoveryConstraints.None,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is SymbolDiscoveryRequest &&
            scope == other.scope &&
            target == other.target &&
            budget == other.budget &&
            constraints == other.constraints

    override fun hashCode(): Int {
        var result = scope.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + budget.hashCode()
        return 31 * result + constraints.hashCode()
    }

    override fun toString(): String =
        "SymbolDiscoveryRequest(scope=$scope, target=$target, budget=$budget, constraints=$constraints)"
}

private fun SymbolDiscoveryKind.isDeclaration(): Boolean = when (this) {
    SymbolDiscoveryKind.CLASS,
    SymbolDiscoveryKind.SYMBOL,
        -> true
    SymbolDiscoveryKind.FILE,
    SymbolDiscoveryKind.TEXT,
        -> false
}
