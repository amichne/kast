package io.github.amichne.kast.indexstore

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
