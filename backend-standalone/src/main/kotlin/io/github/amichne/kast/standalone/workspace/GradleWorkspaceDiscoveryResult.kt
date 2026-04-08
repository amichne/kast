package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.standalone.StandaloneWorkspaceLayout
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspaceDiscoveryDiagnostics(
    val warnings: List<String> = emptyList(),
)

@Serializable
internal data class GradleWorkspaceDiscoveryResult(
    val modules: List<GradleModuleModel>,
    val diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
)

internal data class PhasedDiscoveryResult(
    val initialLayout: StandaloneWorkspaceLayout,
    val enrichmentFuture: CompletableFuture<StandaloneWorkspaceLayout>?,
)
