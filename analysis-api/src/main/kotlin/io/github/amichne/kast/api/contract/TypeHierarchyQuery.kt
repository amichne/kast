@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class TypeHierarchyQuery(
    @DocField(description = "File position identifying the class or interface to expand.")
    val position: FilePosition,
    @DocField(description = "SUPERTYPES, SUBTYPES, or BOTH.", defaultValue = "BOTH")
    val direction: TypeHierarchyDirection = TypeHierarchyDirection.BOTH,
    @DocField(description = "Maximum tree depth to traverse.", defaultValue = "3")
    val depth: Int = 3,
    @DocField(description = "Maximum total nodes to return.", defaultValue = "256")
    val maxResults: Int = 256,
)
