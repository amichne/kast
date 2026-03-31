package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyQuery(
    val position: FilePosition,
    val direction: CallDirection,
    val depth: Int = 3,
)
