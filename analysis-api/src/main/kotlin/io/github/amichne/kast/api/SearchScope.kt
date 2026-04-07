package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

/**
 * Describes the scope and completeness of a reference search or rename operation.
 * LLM consumers can use [exhaustive] to gauge confidence in result completeness.
 */
@Serializable
data class SearchScope(
    val visibility: SymbolVisibility,
    val scope: SearchScopeKind,
    val exhaustive: Boolean,
    val candidateFileCount: Int,
    val searchedFileCount: Int,
)

/** The breadth of files examined during a reference search. */
@Serializable
enum class SearchScopeKind {
    /** Only the declaring file was searched (private/local symbols). */
    FILE,

    /** Only files within the declaring module were searched (internal symbols). */
    MODULE,

    /** Files across dependent modules were searched (public symbols with index hit). */
    DEPENDENT_MODULES,
}
