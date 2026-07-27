package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
enum class ReadCapability {
    RESOLVE_SYMBOL,
    FIND_REFERENCES,
    CALL_HIERARCHY,
    TYPE_HIERARCHY,
    SEMANTIC_INSERTION_POINT,
    DIAGNOSTICS,
    FILE_OUTLINE,
    WORKSPACE_SYMBOL_SEARCH,
    WORKSPACE_SEARCH,
    WORKSPACE_FILES,
    SEMANTIC_GRAPH,
    IMPLEMENTATIONS,
    CODE_ACTIONS,
    COMPLETIONS,
}
