package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleReloadState
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleResolveState
import io.github.amichne.kast.indexer.project.IdeaIndexState
import io.github.amichne.kast.indexer.project.ProjectLifecycleState

data class GradleImportObservation(
    val reload: GradleReloadState,
    val resolve: GradleResolveState,
    val index: IdeaIndexState,
    val lifecycle: ProjectLifecycleState,
    val inventory: GradleModelInventory = GradleModelInventory.empty(),
) {

    val isSettlementCandidate: Boolean
        get() =
            reload == GradleReloadState.COMPLETED &&
                resolve == GradleResolveState.IDLE &&
                index == IdeaIndexState.SMART &&
                lifecycle == ProjectLifecycleState.ACTIVE
}

@ConsistentCopyVisibility
data class GradleModelInventory private constructor(
    val discoveredModules: NonNegativeInt,
    val discoveredSourceRoots: NonNegativeInt,
) {
    companion object {
        @JvmStatic
        fun empty(): GradleModelInventory = GradleModelInventory(NonNegativeInt(0), NonNegativeInt(0))

        /**
         * Proof transition: `(Int, Int) -> GradleModelInventory`.
         *
         * Establishes non-negative module and source-root cardinalities from the
         * IntelliJ model-array boundary. The returned inventory is retained as
         * one observation identity; raw counters do not flow into settlement.
         */
        @JvmStatic
        fun fromIdeaModel(
            discoveredModules: Int,
            discoveredSourceRoots: Int,
        ): GradleModelInventory {
            return GradleModelInventory(
                discoveredModules = NonNegativeInt(discoveredModules),
                discoveredSourceRoots = NonNegativeInt(discoveredSourceRoots),
            )
        }
    }
}
