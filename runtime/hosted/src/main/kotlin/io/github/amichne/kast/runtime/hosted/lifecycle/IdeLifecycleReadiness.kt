package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded existing-project readiness sequence, with at most one Kast-owned model reload. */
internal class IdeLifecycleReadiness(
    private val project: Project,
    private val target: IdeProjectTarget,
    private val root: CanonicalWorkspaceRoot,
    private val query: HostedQueryService,
    private val reloadModel: suspend () -> IdeLifecycleResult,
) {
    private val preparation = WorkspaceReadinessPreparation()

    suspend fun await(): IdeLifecycleResult =
        withTimeoutOrNull(OPERATION_WAIT_MILLIS) {
            while (true) {
                if (project.isDisposed) return@withTimeoutOrNull blocked(IdeLifecycleFailure.DISPOSED)
                when (val step = step()) {
                    ReadinessStep.Wait -> delay(POLL_MILLIS)
                    is ReadinessStep.Finished -> return@withTimeoutOrNull step.result
                }
            }
            @Suppress("UNREACHABLE_CODE") blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
        } ?: blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)

    private suspend fun step(): ReadinessStep =
        when (val action = preparation.observe(query.workspaceReadiness(root))) {
            WorkspacePreparationAction.Ready -> ReadinessStep.Finished(IdeLifecycleResult.Opened(target))
            WorkspacePreparationAction.Wait -> ReadinessStep.Wait
            WorkspacePreparationAction.ReloadModel ->
                when (val result = reloadModel()) {
                    is IdeLifecycleResult.Synced -> ReadinessStep.Wait
                    else -> ReadinessStep.Finished(result)
                }
            is WorkspacePreparationAction.Blocked -> ReadinessStep.Finished(blocked(action.reason))
        }

    private fun blocked(failure: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(failure)

    private sealed interface ReadinessStep {
        data object Wait : ReadinessStep

        data class Finished(val result: IdeLifecycleResult) : ReadinessStep
    }

    private companion object {
        const val OPERATION_WAIT_MILLIS = 120_000L
        const val POLL_MILLIS = 100L
    }
}
