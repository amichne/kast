package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolSelector

private const val MAX_SOURCE_LINE_COUNT = 1_000
private const val MAX_SOURCE_ENTITY_LIMIT = 1_000

sealed interface SourceReadAnchor {
    data class Candidate(val selector: CandidateSelector) : SourceReadAnchor
    data class Symbol(val selector: SymbolSelector) : SourceReadAnchor
    data class Source(val selector: SourceSelector) : SourceReadAnchor
}

enum class BodyKind {
    CALLABLE,
    CLASS,
}

enum class EnclosingRegionKind {
    DECLARATION,
    CALLABLE_BODY,
    CLASS_BODY,
}

sealed interface RegionSelection {
    data object Anchor : RegionSelection
    data class Body(val kind: BodyKind) : RegionSelection
    data object File : RegionSelection
    data class Enclosing(val kind: EnclosingRegionKind) : RegionSelection
}

enum class DeclarationKind {
    CLASSLIKE,
    CONSTRUCTOR,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

enum class DeclarationVisibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PRIVATE,
    LOCAL,
}

enum class DeclarationKindSelectionFailure {
    EMPTY,
}

/** Non-empty canonical declaration-kind selection. */
class DeclarationKindSelection private constructor(
    val values: List<DeclarationKind>,
) {
    companion object {
        fun from(
            raw: Set<DeclarationKind>,
        ): Refinement<DeclarationKindSelection, DeclarationKindSelectionFailure> =
            if (raw.isEmpty()) {
                Refinement.Rejected(DeclarationKindSelectionFailure.EMPTY)
            } else {
                Refinement.Refined(DeclarationKindSelection(raw.sortedBy { it.ordinal }))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is DeclarationKindSelection && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

enum class VisibilitySelectionFailure {
    EMPTY,
}

sealed interface VisibilitySelection {
    data object Any : VisibilitySelection

    class Exact internal constructor(
        val values: List<DeclarationVisibility>,
    ) : VisibilitySelection {
        override fun equals(other: kotlin.Any?): Boolean = other is Exact && values == other.values
        override fun hashCode(): Int = values.hashCode()
    }

    companion object {
        fun exact(
            raw: Set<DeclarationVisibility>,
        ): Refinement<Exact, VisibilitySelectionFailure> =
            if (raw.isEmpty()) {
                Refinement.Rejected(VisibilitySelectionFailure.EMPTY)
            } else {
                Refinement.Refined(Exact(raw.sortedBy { it.ordinal }))
            }
    }
}

enum class Containment {
    DIRECT,
    DESCENDANTS,
}

sealed interface EntityFilter {
    data class Declarations(
        val kinds: DeclarationKindSelection,
        val visibility: VisibilitySelection,
    ) : EntityFilter

    data object Parameters : EntityFilter
    data object Calls : EntityFilter
    data object References : EntityFilter
}

enum class EntitySelectionFailure {
    EMPTY_FILTERS,
    DUPLICATE_FILTER,
}

sealed interface EntitySelection {
    data object None : EntitySelection

    class Matching internal constructor(
        val containment: Containment,
        val filters: List<EntityFilter>,
    ) : EntitySelection

    companion object {
        fun matching(
            containment: Containment,
            filters: List<EntityFilter>,
        ): Refinement<Matching, EntitySelectionFailure> {
            if (filters.isEmpty()) {
                return Refinement.Rejected(EntitySelectionFailure.EMPTY_FILTERS)
            }
            val keys = filters.map(EntityFilter::canonicalKey)
            if (keys.distinct().size != keys.size) {
                return Refinement.Rejected(EntitySelectionFailure.DUPLICATE_FILTER)
            }
            return Refinement.Refined(
                Matching(
                    containment,
                    filters.sortedBy(EntityFilter::canonicalKey),
                ),
            )
        }
    }
}

private fun EntityFilter.canonicalKey(): Int = when (this) {
    is EntityFilter.Declarations -> 0
    EntityFilter.Parameters -> 1
    EntityFilter.Calls -> 2
    EntityFilter.References -> 3
}

enum class LineCountFailure {
    NEGATIVE,
    TOO_LARGE,
}

@JvmInline
value class LineCount private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<LineCount, LineCountFailure> = when {
            raw < 0 -> Refinement.Rejected(LineCountFailure.NEGATIVE)
            raw > MAX_SOURCE_LINE_COUNT -> Refinement.Rejected(LineCountFailure.TOO_LARGE)
            else -> Refinement.Refined(LineCount(raw))
        }
    }
}

sealed interface TextProjection {
    data object Complete : TextProjection
    data object None : TextProjection

    data class Window internal constructor(
        val beforeLines: LineCount,
        val afterLines: LineCount,
    ) : TextProjection

    companion object {
        fun window(beforeLines: LineCount, afterLines: LineCount): Window =
            Window(beforeLines, afterLines)
    }
}

enum class SourceEntityLimitFailure {
    NOT_POSITIVE,
    TOO_LARGE,
}

@JvmInline
value class SourceEntityLimit private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<SourceEntityLimit, SourceEntityLimitFailure> = when {
            raw < 1 -> Refinement.Rejected(SourceEntityLimitFailure.NOT_POSITIVE)
            raw > MAX_SOURCE_ENTITY_LIMIT -> Refinement.Rejected(SourceEntityLimitFailure.TOO_LARGE)
            else -> Refinement.Refined(SourceEntityLimit(raw))
        }
    }
}

enum class SourceTextByteLimitFailure {
    NOT_POSITIVE,
}

@JvmInline
value class SourceTextByteLimit private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<SourceTextByteLimit, SourceTextByteLimitFailure> =
            if (raw < 1L) {
                Refinement.Rejected(SourceTextByteLimitFailure.NOT_POSITIVE)
            } else {
                Refinement.Refined(SourceTextByteLimit(raw))
            }
    }
}

enum class SourceReadContinuationFailure {
    INVALID_FORMAT,
}

@JvmInline
value class SourceReadContinuation private constructor(val value: String) {
    companion object {
        private const val PREFIX = "source-read-continuation-v1|"
        private const val DIGEST_LENGTH = 64

        fun parse(
            raw: String,
        ): Refinement<SourceReadContinuation, SourceReadContinuationFailure> {
            val digest = raw.removePrefix(PREFIX)
            return if (
                raw.length == PREFIX.length + DIGEST_LENGTH &&
                digest.length == DIGEST_LENGTH &&
                digest.all { it in '0'..'9' || it in 'a'..'f' }
            ) {
                Refinement.Refined(SourceReadContinuation(raw))
            } else {
                Refinement.Rejected(SourceReadContinuationFailure.INVALID_FORMAT)
            }
        }
    }
}

sealed interface SourceReadPage {
    data object First : SourceReadPage
    data class Continue(val continuation: SourceReadContinuation) : SourceReadPage
}

data class SourceReadRequest(
    val anchor: SourceReadAnchor,
    val region: RegionSelection,
    val entities: EntitySelection,
    val text: TextProjection,
    val entityLimit: SourceEntityLimit,
    val textByteLimit: SourceTextByteLimit,
    val page: SourceReadPage,
)
