package io.github.amichne.kast.api.contract

import kotlinx.serialization.Serializable

@Serializable
enum class SymbolKind {
    CLASS,
    INTERFACE,
    OBJECT,
    FUNCTION,
    PROPERTY,
    PARAMETER,
    UNKNOWN,
}
