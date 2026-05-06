package io.github.amichne.kast.indexstore.api.reference

import kotlinx.serialization.Serializable

@Serializable
enum class DeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    FUNCTION,
    PROPERTY,
    TYPEALIAS,
    ENUM_CLASS,
    ENUM_ENTRY,
    CONSTRUCTOR,
}
