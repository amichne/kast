package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
enum class TypeHierarchyDirection {
    SUPERTYPES,
    SUBTYPES,
    BOTH,
}
