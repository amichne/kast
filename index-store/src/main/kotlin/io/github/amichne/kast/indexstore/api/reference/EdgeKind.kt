package io.github.amichne.kast.indexstore.api.reference

import kotlinx.serialization.Serializable

@Serializable
enum class EdgeKind {
    CALL,
    TYPE_REF,
    INHERITANCE,
    OVERRIDE,
    IMPORT,
    ANNOTATION,
    UNKNOWN,
}
