package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyQuery(
    val position: FilePosition,
    val direction: CallDirection,
    val depth: Int = 3,
    val maxTotalCalls: Int = 256,
    val maxChildrenPerNode: Int = 64,
    val timeoutMillis: Long? = null,
    val persistToGitShaCache: Boolean = false,
)
