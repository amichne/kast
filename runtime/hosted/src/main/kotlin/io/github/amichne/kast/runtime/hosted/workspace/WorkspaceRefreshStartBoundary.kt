package io.github.amichne.kast.runtime.hosted.workspace

/** Entering a scheduler is the point after which an exception alone cannot prove native settlement. */
internal class WorkspaceRefreshStartBoundary {
    private enum class Phase {
        NOT_STARTED,
        MAY_HAVE_STARTED,
    }

    private var phase = Phase.NOT_STARTED

    fun mayHaveStarted() {
        phase = Phase.MAY_HAVE_STARTED
    }

    fun run(
        failure: (RuntimeException) -> WorkspaceRefreshEffectResult,
        notStarted: (WorkspaceRefreshEffectResult) -> Unit,
        unsettled: (WorkspaceRefreshEffectResult) -> Unit,
        block: () -> Unit,
    ) {
        runCatching(block).onFailure { exception ->
            // Only native RuntimeExceptions enter the finite start decision; errors retain their original propagation.
            if (exception !is RuntimeException) throw exception
            val result = failure(exception)
            when (phase) {
                Phase.NOT_STARTED -> notStarted(result)
                Phase.MAY_HAVE_STARTED -> unsettled(result)
            }
        }
    }
}
