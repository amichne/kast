package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.standalone.WorkspaceLayout
import java.util.concurrent.CompletableFuture

internal data class PhasedDiscoveryResult(
    val initialLayout: WorkspaceLayout,
    val enrichmentFuture: CompletableFuture<WorkspaceLayout>?,
)
