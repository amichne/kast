package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.RuntimeDigest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import io.github.amichne.kast.protocol.wire.metadata.SidecarTelemetryOutput
import io.github.amichne.kast.protocol.wire.metadata.SidecarTelemetryOutputFailure
import io.github.amichne.kast.kernel.Refinement
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
        val telemetry: ProductTelemetryObservation,
    ) : ProductWorkspaceObservation
}

/** Deterministic telemetry configuration observation that never starts or admits a runtime. */
sealed interface ProductTelemetryObservation {
    data class Enabled(
        val output: SidecarTelemetryOutput,
    ) : ProductTelemetryObservation

    data class EndpointRejected(
        val failure: RuntimeEndpointFailure,
    ) : ProductTelemetryObservation

    data class OutputRejected(
        val failure: SidecarTelemetryOutputFailure,
    ) : ProductTelemetryObservation

    data class CacheRejected(
        val failure: SidecarCacheLifecycleFailure,
    ) : ProductTelemetryObservation
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
    private val endpointLocator: RuntimeEndpointLocator,
) : ProductInspector {
    override fun inspect(start: Path): ProductInspection {
        val workspace = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Rejected ->
                ProductWorkspaceObservation.RootRejected(discovery.failure)
            is CanonicalRootDiscovery.Discovered -> {
                val cache = cacheLifecycle.observe(discovery.root.path)
                ProductWorkspaceObservation.Observed(
                    discovery.root,
                    cache,
                    endpointLocator.telemetryObservation(discovery.root, cache),
                )
            }
        }
        return ProductInspection(control, workspace)
    }

    private fun RuntimeEndpointLocator.telemetryObservation(
        root: CanonicalRoot,
        cache: RootSidecarCacheObservation,
    ): ProductTelemetryObservation = when (val resolution = locate(root)) {
        is RuntimeEndpointResolution.Rejected -> ProductTelemetryObservation.EndpointRejected(
            resolution.failure,
        )
        is RuntimeEndpointResolution.Resolved -> {
            val exact = when (cache) {
                is RootSidecarCacheObservation.Identified -> when (
                    val refined = resolution.endpoint.forSidecarCache(
                        cache.status.cacheIdentity,
                        cache.status.semanticRuntimeId,
                    )
                ) {
                    is RuntimeEndpointResolution.Resolved -> refined.endpoint
                    is RuntimeEndpointResolution.Rejected -> return ProductTelemetryObservation
                        .EndpointRejected(refined.failure)
                }
                RootSidecarCacheObservation.Absent -> resolution.endpoint
                is RootSidecarCacheObservation.Rejected -> return ProductTelemetryObservation
                    .CacheRejected(cache.failure)
            }
            when (
                val output = SidecarTelemetryOutput.fromSocketPath(exact.socketPath.toString())
            ) {
                is Refinement.Refined -> ProductTelemetryObservation.Enabled(output.value)
                is Refinement.Rejected -> ProductTelemetryObservation.OutputRejected(output.failure)
            }
        }
    }
}
