package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/** An identity receipt, independent of whether its derived cache state is usable. */
@ConsistentCopyVisibility
data class RootSidecarCacheReference
internal constructor(
    val cacheIdentity: String,
    val semanticRuntimeId: SemanticRuntimeId,
    val cacheRoot: Path,
    val ideaHome: Path,
)

/** All admitted non-quarantined identities for one physical workspace, not a selected cache. */
class RootSidecarCacheInventory
internal constructor(
    val root: Path,
    caches: List<RootSidecarCacheReference>,
) {
    val caches: List<RootSidecarCacheReference> = caches.sortedBy { it.cacheRoot.toString() }

    /** Cache history may retain an installation choice, but never chooses semantic identity. */
    fun retainedIdeHome(): Refinement<StartupIdeHome, SidecarCacheLifecycleFailure> {
        val homes = caches.map(RootSidecarCacheReference::ideaHome).distinct()
        return when (homes.size) {
            0 -> Refinement.Refined(StartupIdeHome.Standard)
            1 -> Refinement.Refined(StartupIdeHome.Explicit(homes.single()))
            else -> Refinement.Rejected(SidecarCacheLifecycleFailure.AMBIGUOUS_IDENTITY)
        }
    }
}

/** Only successful exact-owner termination can construct the input to cache quarantine. */
class StoppedSidecarCaches
private constructor(
    val inventory: RootSidecarCacheInventory,
    removed: Set<RuntimeEndpointArtifact>,
) {
    val removed: Set<RuntimeEndpointArtifact> = removed.toSet()

    companion object {
        /**
         * Inventory -> exact endpoints -> stopped inventory. Every endpoint is admitted before any stop; every stop
         * succeeds before the filesystem owner receives quarantine authority. The caller holds the root startup lock
         * through this transition and quarantine.
         */
        fun stopAll(
            inventory: RootSidecarCacheInventory,
            base: RuntimeEndpoint,
            lifecycle: RuntimeLifecycleController,
        ): Refinement<StoppedSidecarCaches, RuntimeAdmissionFailure> {
            if (base.root.path != inventory.root) {
                return Refinement.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
            }
            val endpoints = linkedSetOf(base)
            for (cache in inventory.caches) {
                when (
                    val resolved =
                        base.forSidecarCache(
                            cache.cacheIdentity,
                            cache.semanticRuntimeId,
                            cache.cacheRoot,
                        )
                ) {
                    is RuntimeEndpointResolution.Resolved -> endpoints += resolved.endpoint
                    is RuntimeEndpointResolution.Rejected ->
                        return Refinement.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
                }
            }
            val removed = linkedSetOf<RuntimeEndpointArtifact>()
            for (endpoint in endpoints) {
                when (val stopped = lifecycle.stop(endpoint)) {
                    is RuntimeStopResult.Stopped -> removed += stopped.removed
                    is RuntimeStopResult.Rejected ->
                        return Refinement.Rejected(RuntimeAdmissionFailure.StopRejected(stopped.failure))
                }
            }
            return Refinement.Refined(StoppedSidecarCaches(inventory, removed))
        }
    }
}
