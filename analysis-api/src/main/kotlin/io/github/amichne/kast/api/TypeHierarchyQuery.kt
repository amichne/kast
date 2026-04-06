package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class TypeHierarchyQuery(
    val position: FilePosition,
    val direction: TypeHierarchyDirection = TypeHierarchyDirection.BOTH,
    val depth: Int = 3,
    val maxResults: Int = 256,
)
