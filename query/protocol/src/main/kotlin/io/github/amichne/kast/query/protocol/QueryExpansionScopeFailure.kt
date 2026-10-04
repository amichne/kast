package io.github.amichne.kast.query.protocol

enum class QueryExpansionScopeFailure {
    DUPLICATE_SOURCE_SET,
    SOURCE_SET_REJECTED,
    EMPTY_SOURCE_SET,
    DIRECTORY_REJECTED,
}
