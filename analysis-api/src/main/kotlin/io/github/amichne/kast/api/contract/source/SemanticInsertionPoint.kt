@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

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
    @DocField(description = "File position near the desired insertion location.")
    val position: FilePosition,
    @DocField(description = "Where to compute the insertion point relative to the position.")
    val target: SemanticInsertionTarget,
)

@Serializable
data class SemanticInsertionResult(
    @DocField(description = "Zero-based byte offset where new code should be inserted.")
    val insertionOffset: Int,
    @DocField(description = "Absolute path of the file containing the insertion point.")
    val filePath: String,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
