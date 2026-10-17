package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded existing-project readiness sequence, with at most one Kast-owned model reload. */
internal class IdeLifecycleReadiness(
    private val isDisposed: () -> Boolean,
    private val target: IdeProjectTarget,
    private val observe: () -> WorkspaceCapabilityReadiness,
    private val reloadModel: suspend () -> IdeLifecycleResult,
    private val operation: NativeWorkspacePreparation,
) {
    private val preparation = WorkspaceReadinessPreparation()

    suspend fun await(): IdeLifecycleResult {
        val remaining =
            when (val admitted = operation.remaining()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
            }
        return withTimeoutOrNull(remaining.value) {
            while (true) {
                if (isDisposed()) return@withTimeoutOrNull blocked(IdeLifecycleFailure.DISPOSED)
                when (val step = step()) {
                    ReadinessStep.Wait -> delay(POLL_MILLIS)
                    is ReadinessStep.Finished -> return@withTimeoutOrNull step.result
                }
            }
            @Suppress("UNREACHABLE_CODE") blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
        } ?: blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED)
    }

    private suspend fun step(): ReadinessStep =
        when (val action = preparation.observe(observe())) {
            WorkspacePreparationAction.Ready -> {
                operation.enter(io.github.amichne.kast.protocol.contract.IdeLifecycleStage.ADMISSION)
                ReadinessStep.Finished(IdeLifecycleResult.Opened(target))
            }
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
        const val POLL_MILLIS = 100L
    }
}
