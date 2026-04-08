package io.github.amichne.kast.standalone.workspace

import kotlinx.serialization.Serializable

@Serializable
internal data class GradleWorkspaceDiscoveryResult(
    val modules: List<GradleModuleModel>,
    val diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
)
