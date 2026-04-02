package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyResult(
    val root: CallNode,
    val totalCalls: Int = 0,
    val truncated: Boolean = false,
    val truncationReasons: Set<CallHierarchyTruncationReason> = emptySet(),
    val gitCommitSha: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)
