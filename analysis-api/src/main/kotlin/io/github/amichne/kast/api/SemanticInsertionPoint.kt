package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
enum class SemanticInsertionTarget {
    CLASS_BODY_START,
    CLASS_BODY_END,
    FILE_TOP,
    FILE_BOTTOM,
    AFTER_IMPORTS,
}

@Serializable
data class SemanticInsertionQuery(
    val position: FilePosition,
    val target: SemanticInsertionTarget,
)

@Serializable
data class SemanticInsertionResult(
    val insertionOffset: Int,
    val filePath: String,
    val schemaVersion: Int = SCHEMA_VERSION,
)
