package io.github.amichne.kast.shared.analysis

import io.github.amichne.kast.api.ValidationException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Matches symbol names against a search pattern.
 *
 * When [regex] is false, performs case-insensitive substring matching (similar to
 * IntelliJ's "Go to Class" behavior). When [regex] is true, compiles the pattern
 * as a [java.util.regex.Pattern] and matches against the full symbol name.
 */
class SymbolSearchMatcher private constructor(
    private val predicate: (String) -> Boolean,
) {
    fun matches(name: String): Boolean = predicate(name)

    companion object {
        fun create(pattern: String, regex: Boolean): SymbolSearchMatcher {
            return if (regex) {
                val compiled = try {
                    Pattern.compile(pattern)
                } catch (e: PatternSyntaxException) {
                    throw ValidationException(
                        message = "Invalid regex pattern: ${e.description}",
                        details = mapOf("pattern" to pattern),
                    )
                }
                SymbolSearchMatcher { name -> compiled.matcher(name).find() }
            } else {
                val lowerPattern = pattern.lowercase()
                SymbolSearchMatcher { name -> name.lowercase().contains(lowerPattern) }
            }
        }
    }
}
