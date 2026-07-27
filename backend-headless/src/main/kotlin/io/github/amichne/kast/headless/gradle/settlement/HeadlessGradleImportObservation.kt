package io.github.amichne.kast.headless

data class HeadlessGradleImportObservation(
    val reload: HeadlessGradleReloadState,
    val resolve: HeadlessGradleResolveState,
    val index: HeadlessIdeaIndexState,
    val lifecycle: HeadlessProjectLifecycleState,
) {
    val isSettlementCandidate: Boolean
        get() =
            reload == HeadlessGradleReloadState.COMPLETED &&
                resolve == HeadlessGradleResolveState.IDLE &&
                index == HeadlessIdeaIndexState.SMART &&
                lifecycle == HeadlessProjectLifecycleState.ACTIVE
}
