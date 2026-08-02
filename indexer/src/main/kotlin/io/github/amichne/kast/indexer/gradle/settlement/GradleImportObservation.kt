package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.indexer.gradle.bootstrap.GradleReloadState
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleResolveState
import io.github.amichne.kast.indexer.project.IdeaIndexState
import io.github.amichne.kast.indexer.project.ProjectLifecycleState

data class GradleImportObservation(
    val reload: GradleReloadState,
    val resolve: GradleResolveState,
    val index: IdeaIndexState,
    val lifecycle: ProjectLifecycleState,
) {
    val isSettlementCandidate: Boolean
        get() =
            reload == GradleReloadState.COMPLETED &&
                resolve == GradleResolveState.IDLE &&
                index == IdeaIndexState.SMART &&
                lifecycle == ProjectLifecycleState.ACTIVE
}
