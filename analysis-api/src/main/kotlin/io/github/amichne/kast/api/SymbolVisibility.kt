package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

/**
 * Kotlin/Java visibility of a resolved symbol.
 *
 * Maps Kotlin modifiers (`private`, `internal`, `protected`, `public`) and Java
 * access levels to a closed set of values that scope-pruning logic can branch on.
 * [LOCAL] denotes declarations inside a function or block body that are invisible
 * outside the enclosing statement scope.
 */
@Serializable
enum class SymbolVisibility {
    PUBLIC,
    INTERNAL,
    PROTECTED,
    PRIVATE,

    /** Declaration inside a function body or block expression — unreachable outside the file. */
    LOCAL,

    /** Visibility could not be determined (e.g. synthetic or non-Kotlin/Java elements). */
    UNKNOWN,
}
