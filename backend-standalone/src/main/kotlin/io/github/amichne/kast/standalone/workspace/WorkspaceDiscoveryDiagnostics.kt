package io.github.amichne.kast.standalone.workspace

import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspaceDiscoveryDiagnostics(
    val warnings: List<String> = emptyList(),
)
