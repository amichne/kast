package io.github.amichne.kast.indexstore.api.reference

import kotlinx.serialization.Serializable

@Serializable
enum class DeclarationVisibility {
    PUBLIC,
    INTERNAL,
    PROTECTED,
    PRIVATE,
    LOCAL,
}
