package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
enum class DeclarationVisibility {
    PUBLIC,
    INTERNAL,
    PROTECTED,
    PRIVATE,
    LOCAL,
}
