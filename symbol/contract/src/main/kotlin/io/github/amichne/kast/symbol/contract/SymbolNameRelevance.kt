package io.github.amichne.kast.symbol.contract

/** Lexical evidence only; ranking never grants compiler authority. */
enum class SymbolNameRelevance {
    EXACT,
    CASE_INSENSITIVE,
    ONE_EDIT,
    SUBSEQUENCE,
    UNMATCHED,
}

/** Linear, allocation-bounded typo admission: one insertion, deletion, substitution, or adjacent transposition. */
fun SymbolDiscoveryPattern.relevance(name: String): SymbolNameRelevance {
    if (value == name) return SymbolNameRelevance.EXACT
    val pattern = value.lowercase(java.util.Locale.ROOT)
    val candidate = name.lowercase(java.util.Locale.ROOT)
    if (pattern == candidate) return SymbolNameRelevance.CASE_INSENSITIVE
    if (oneEditApart(pattern, candidate)) return SymbolNameRelevance.ONE_EDIT
    var index = 0
    for (character in candidate) {
        if (index < pattern.length && pattern[index] == character) index++
    }
    return if (index == pattern.length) SymbolNameRelevance.SUBSEQUENCE else SymbolNameRelevance.UNMATCHED
}

private fun oneEditApart(left: String, right: String): Boolean {
    if (kotlin.math.abs(left.length - right.length) > 1) return false
    var prefix = 0
    while (prefix < minOf(left.length, right.length) && left[prefix] == right[prefix]) prefix++
    if (prefix == minOf(left.length, right.length)) return true
    return when {
        left.length < right.length ->
            left.regionMatches(
                thisOffset = prefix,
                other = right,
                otherOffset = prefix + 1,
                length = left.length - prefix,
            )
        left.length > right.length ->
            left.regionMatches(
                thisOffset = prefix + 1,
                other = right,
                otherOffset = prefix,
                length = right.length - prefix,
            )
        left.regionMatches(
            thisOffset = prefix + 1,
            other = right,
            otherOffset = prefix + 1,
            length = left.length - prefix - 1,
        ) -> true
        prefix + 1 < left.length && left[prefix] == right[prefix + 1] && left[prefix + 1] == right[prefix] ->
            left.regionMatches(
                thisOffset = prefix + 2,
                other = right,
                otherOffset = prefix + 2,
                length = left.length - prefix - 2,
            )
        else -> false
    }
}

/** Deterministic best-first order is part of the discovery request, retained before exact refinement. */
fun SymbolDiscoveryRequest.candidateOrder(): Comparator<SymbolDiscoveryCandidate> {
    val name = target as? SymbolDiscoveryTarget.Name
    return if (name != null && name.match == SymbolDiscoveryMatch.FUZZY)
        compareBy<SymbolDiscoveryCandidate> { name.pattern.relevance(it.name.value) }.thenBy { it }
    else naturalOrder()
}
