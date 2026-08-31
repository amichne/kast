package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.RuntimeDigest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import java.nio.file.Path

/** Installed control plus the sole private-sidecar runtime identity. */
data class SidecarProductIdentity(
    val productVersion: KastPluginVersion,
    val runtimeId: SemanticRuntimeId,
    val supportedRuntime: SupportedIdeRuntimePair,
    val payloadDigest: RuntimeDigest,
)

/** One complete local observation that never discovers or admits a hosted IDE endpoint. */
data class ProductInspection(
    val control: SidecarProductIdentity,
    val workspace: ProductWorkspaceObservation,
)

sealed interface ProductWorkspaceObservation {
    data class RootRejected(
        val failure: CanonicalRootFailure,
    ) : ProductWorkspaceObservation

    data class Observed(
        val root: CanonicalRoot,
        val cache: RootSidecarCacheObservation,
    ) : ProductWorkspaceObservation
}

fun interface ProductInspector {
    /**
     * Proof transition: `Path -> ProductInspection`.
     *
     * Preserves the admitted sidecar identity and directly observes only canonical root and
     * Kast-owned cache evidence. No runtime demand or user-IDE endpoint admission is possible.
     */
    fun inspect(start: Path): ProductInspection
}

class SidecarProductInspector(
    private val control: SidecarProductIdentity,
    private val rootDiscovery: CanonicalRootDiscoverer,
    private val cacheLifecycle: RootSidecarCacheLifecycle,
) : ProductInspector {
    override fun inspect(start: Path): ProductInspection {
        val workspace = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Rejected ->
                ProductWorkspaceObservation.RootRejected(discovery.failure)
            is CanonicalRootDiscovery.Discovered -> ProductWorkspaceObservation.Observed(
                discovery.root,
                cacheLifecycle.observe(discovery.root.path),
            )
        }
        return ProductInspection(control, workspace)
    }
}
