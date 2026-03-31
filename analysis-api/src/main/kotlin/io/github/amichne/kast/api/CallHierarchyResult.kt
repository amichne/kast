package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class CallHierarchyResult(
    val root: CallNode,
    val schemaVersion: Int = SCHEMA_VERSION,
)
