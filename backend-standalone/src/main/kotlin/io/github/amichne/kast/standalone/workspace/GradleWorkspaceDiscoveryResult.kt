package io.github.amichne.kast.standalone.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class GradleWorkspaceDiscoveryResult(
    val modules: List<GradleModuleModel>,
    val diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
    @Transient val toolingApiSucceeded: Boolean = true,
)
