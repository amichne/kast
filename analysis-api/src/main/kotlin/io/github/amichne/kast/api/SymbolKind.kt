package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class SymbolKind {
    CLASS,
    INTERFACE,
    OBJECT,
    FUNCTION,
    PROPERTY,
    CONSTRUCTOR,
    ENUM_ENTRY,
    TYPE_ALIAS,
    PACKAGE,
    PARAMETER,
    LOCAL_VARIABLE,
    UNKNOWN,
}
