package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ServerInstanceDescriptor(
    val workspaceRoot: String,
    val backendName: String,
    val backendVersion: String,
    val host: String,
    val port: Int,
    val token: String? = null,
    val pid: Long = ProcessHandle.current().pid(),
    val schemaVersion: Int = SCHEMA_VERSION,
)
