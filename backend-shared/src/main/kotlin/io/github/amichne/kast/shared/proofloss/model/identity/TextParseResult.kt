package io.github.amichne.kast.shared.proofloss.model

sealed interface TextParseResult<out T> {
    data class Valid<T>(val value: T) : TextParseResult<T>
    data object Blank : TextParseResult<Nothing>
}

internal inline fun <T> parseText(
    raw: String,
    construct: (String) -> T,
): TextParseResult<T> =
    raw.trim()
        .takeIf(String::isNotEmpty)
        ?.let { TextParseResult.Valid(construct(it)) }
        ?: TextParseResult.Blank
