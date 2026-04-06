package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class TypeHierarchyDirection {
    SUPERTYPES,
    SUBTYPES,
    BOTH,
}
