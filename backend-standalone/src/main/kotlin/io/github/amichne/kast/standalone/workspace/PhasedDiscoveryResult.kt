package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.standalone.StandaloneWorkspaceLayout
import java.util.concurrent.CompletableFuture

internal data class PhasedDiscoveryResult(
    val initialLayout: StandaloneWorkspaceLayout,
    val enrichmentFuture: CompletableFuture<StandaloneWorkspaceLayout>?,
)
