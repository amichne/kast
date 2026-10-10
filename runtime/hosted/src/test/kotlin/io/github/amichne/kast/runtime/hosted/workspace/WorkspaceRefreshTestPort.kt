package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason

internal class WorkspaceRefreshTestPort : WorkspaceRefreshPort {
    val effects = mutableListOf<WorkspaceRefreshEffect>()
    val callbacks = ArrayDeque<(WorkspaceRefreshEffectResult) -> Unit>()
    var incrementalEffects = 0
    var ready = false

    override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        effects += effect
        callbacks += complete
    }

    private val facts =
        WorkspaceReadinessFixture(
            (CanonicalWorkspaceRoot.fromCanonicalPath(java.nio.file.Path.of("/workspace")) as Refinement.Refined).value
        )

    override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) {
        incrementalEffects++
        start(WorkspaceRefreshEffect.FILE_REFRESH, complete)
    }

    override fun readiness(): WorkspaceCapabilityReadiness =
        if (ready) facts.ready()
        else
            WorkspaceCapabilityReadiness.Pending(
                facts.identity,
                WorkspaceReadinessReason.INDEXING,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            )

    fun advanceModel() = facts.advance()

    fun finish(result: WorkspaceRefreshEffectResult) = callbacks.removeFirst()(result)
}
