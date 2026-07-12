package io.github.amichne.kast.shared.proofloss.model

sealed interface TextParseResult<out T> {
    data class Valid<T>(val value: T) : TextParseResult<T>
    data object Blank : TextParseResult<Nothing>
}

@JvmInline
value class PredicateId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<PredicateId> = parseText(raw, ::PredicateId)
    }
}

@JvmInline
value class BoundaryId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TextParseResult<BoundaryId> = parseText(raw, ::BoundaryId)
    }
}

internal inline fun <T> parseText(
    raw: String,
    construct: (String) -> T,
): TextParseResult<T> =
    raw.trim()
        .takeIf(String::isNotEmpty)
        ?.let { TextParseResult.Valid(construct(it)) }
        ?: TextParseResult.Blank

sealed interface ArgumentIndexParseResult {
    data class Valid(val value: ArgumentIndex) : ArgumentIndexParseResult
    data class Negative(val value: Int) : ArgumentIndexParseResult
}

@JvmInline
value class ArgumentIndex private constructor(val value: Int) : Comparable<ArgumentIndex> {
    override fun compareTo(other: ArgumentIndex): Int = value.compareTo(other.value)

    companion object {
        fun parse(raw: Int): ArgumentIndexParseResult =
            if (raw >= 0) ArgumentIndexParseResult.Valid(ArgumentIndex(raw))
            else ArgumentIndexParseResult.Negative(raw)
    }
}
