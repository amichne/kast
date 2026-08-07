package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.indexer.gradle.bootstrap.GradleReloadState
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleResolveState
import io.github.amichne.kast.indexer.project.IdeaIndexState
import io.github.amichne.kast.indexer.project.ProjectLifecycleState

data class GradleImportObservation @JvmOverloads constructor(
    val reload: GradleReloadState,
    val resolve: GradleResolveState,
    val index: IdeaIndexState,
    val lifecycle: ProjectLifecycleState,
    val discoveredModuleCount: Int = 0,
    val discoveredSourceRootCount: Int = 0,
) {
    init {
        require(discoveredModuleCount >= 0) { "discoveredModuleCount must not be negative" }
        require(discoveredSourceRootCount >= 0) { "discoveredSourceRootCount must not be negative" }
    }

    val isSettlementCandidate: Boolean
        get() =
            reload == GradleReloadState.COMPLETED &&
                resolve == GradleResolveState.IDLE &&
                index == IdeaIndexState.SMART &&
                lifecycle == ProjectLifecycleState.ACTIVE
}
