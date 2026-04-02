package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ServerInstanceDescriptor(
    val workspaceRoot: String,
    val backendName: String,
    val backendVersion: String,
    val transport: String = "uds",
    val socketPath: String,
    val pid: Long = ProcessHandle.current().pid(),
    val schemaVersion: Int = SCHEMA_VERSION,
)
