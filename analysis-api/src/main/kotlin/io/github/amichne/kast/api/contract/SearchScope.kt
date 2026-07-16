@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

/**
 * Describes the scope and completeness of a reference search or rename operation.
 * LLM consumers can use [exhaustive] for result-set completeness and [candidateCoverage]
 * to distinguish ordinary pagination from an incomplete semantic search.
 */
@Serializable
data class SearchScope(
    @DocField(description = "Visibility of the target symbol, used to determine search breadth.")
    val visibility: SymbolVisibility,
    @DocField(description = "The breadth of files examined: FILE, MODULE, or DEPENDENT_MODULES.")
    val scope: SearchScopeKind,
    @DocField(description = "True only when candidate coverage completed and no result continuation remains.")
    val exhaustive: Boolean,
    @DocField(description = "Whether the underlying candidate search completed without a semantic or budget limitation.")
    val candidateCoverage: CandidateCoverage = if (exhaustive) CandidateCoverage.COMPLETE else CandidateCoverage.PARTIAL,
    @DocField(description = "Total number of files that could contain references.")
    val candidateFileCount: Int,
    @DocField(description = "Number of files actually examined during the search.")
    val searchedFileCount: Int,
) {
    @Serializable
    enum class CandidateCoverage {
        COMPLETE,
        PARTIAL,
    }
}

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
