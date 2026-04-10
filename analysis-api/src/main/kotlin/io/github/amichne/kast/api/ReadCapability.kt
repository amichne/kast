package io.github.amichne.kast.api

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
}
