package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

internal data class ForceResetExecution(
    val runtime: ForceDaemonRuntime = NativeForceDaemonRuntime,
    val observe: (ResetObservation) -> Unit = { System.err.println(it.asJson()) },
)

internal fun forceResetInstallation(
    root: Path,
    home: Path,
    operation: ForceResetOperation,
    execution: ForceResetExecution = ForceResetExecution(),
    removalScope: ResetRemovalScope = ResetRemovalScope.SelectedRoot,
): ForceResetOutcome {
    val selected =
        when (val admitted = ForceResetRoot.admit(root, home)) {
            is ForceResetRootAdmission.Selected -> admitted.root
            ForceResetRootAdmission.Rejected ->
                return ForceResetOutcome.Rejected(operation, ForceResetStage.ADMISSION, ForceResetFailure.ROOT_REJECTED)
        }
    val fence =
        when (val acquired = ForceResetFence.acquire(selected, removalScope)) {
            is ResetFenceAcquisition.Acquired -> acquired.fence
            is ResetFenceAcquisition.Rejected ->
                return ForceResetOutcome.Rejected(operation, ForceResetStage.FENCE, acquired.failure)
        }
    var released: ResetEffect
    val result =
        try {
            executeAcquired(fence, home, operation, execution, removalScope)
        } finally {
            released = fence.release()
        }
    return when (val release = released) {
        ResetEffect.Completed -> result
        is ResetEffect.Rejected -> rejected(operation, ForceResetStage.FINAL_VERIFICATION, release.failure, execution)
    }
}

private fun executeAcquired(
    fence: ForceResetFence,
    home: Path,
    operation: ForceResetOperation,
    execution: ForceResetExecution,
    removalScope: ResetRemovalScope,
): ForceResetOutcome {
    execution.observe(ResetObservation.Started(operation, ForceResetStage.FENCE))
    val fenced =
        when (val detached = FencedReset.detach(fence)) {
            is ResetFencing.Fenced -> detached.reset
            is ResetFencing.Rejected -> return rejected(operation, ForceResetStage.FENCE, detached.failure, execution)
        }
    execution.observe(ResetObservation.Completed(operation, ForceResetStage.FENCE))
    return executeFenced(fenced, home, operation, execution, removalScope)
}

private fun executeFenced(
    fenced: FencedReset,
    home: Path,
    operation: ForceResetOperation,
    execution: ForceResetExecution,
    removalScope: ResetRemovalScope,
): ForceResetOutcome {
    execution.observe(ResetObservation.Started(operation, ForceResetStage.DAEMONS))
    val retired =
        when (val retirement = QuiescentReset.retire(fenced, home, execution.runtime)) {
            is ResetRetirement.Retired -> retirement.reset
            is ResetRetirement.Rejected ->
                return retained(fenced, operation, ForceResetStage.DAEMONS, retirement.failure, execution)
        }
    execution.observe(ResetObservation.Completed(operation, ForceResetStage.DAEMONS))
    execution.observe(ResetObservation.Started(operation, ForceResetStage.CLEANUP))
    val erased =
        when (val erasure = ErasedReset.erase(retired)) {
            is ResetErasure.Erased -> erasure.reset
            is ResetErasure.Rejected ->
                return retained(
                    fenced,
                    operation,
                    ForceResetStage.CLEANUP,
                    erasure.failure,
                    execution,
                )
        }
    execution.observe(ResetObservation.Completed(operation, ForceResetStage.CLEANUP))
    return finishRemoval(erased, home, execution, removalScope)
}

private fun finishRemoval(
    erased: ErasedReset,
    home: Path,
    execution: ForceResetExecution,
    removalScope: ResetRemovalScope,
): ForceResetOutcome {
    val operation = ForceResetOperation.UNINSTALL
    execution.observe(ResetObservation.Started(operation, ForceResetStage.FINAL_VERIFICATION))
    val exterior =
        when (removalScope) {
            ResetRemovalScope.SelectedRoot -> ResetEffect.Completed
            is ResetRemovalScope.Public -> removalScope.scope.removeExterior(erased, execution.runtime)
        }
    if (exterior is ResetEffect.Rejected)
        return rejected(operation, ForceResetStage.FINAL_VERIFICATION, exterior.failure, execution)
    val absent = ForceDaemonRetirement(execution.runtime).retire(erased.root, home)
    if (absent is ResetEffect.Rejected)
        return rejected(operation, ForceResetStage.FINAL_VERIFICATION, absent.failure, execution)
    return when (val lifted = erased.fence.liftRemoved(erased)) {
        ResetEffect.Completed -> {
            execution.observe(ResetObservation.Completed(operation, ForceResetStage.FINAL_VERIFICATION))
            ForceResetOutcome.Removed(erased.root.toString())
        }
        is ResetEffect.Rejected -> rejected(operation, ForceResetStage.FINAL_VERIFICATION, lifted.failure, execution)
    }
}

private fun rejected(
    operation: ForceResetOperation,
    stage: ForceResetStage,
    failure: ForceResetFailure,
    execution: ForceResetExecution,
): ForceResetOutcome {
    execution.observe(ResetObservation.Rejected(operation, stage, failure))
    return ForceResetOutcome.Rejected(operation, stage, failure)
}

private fun retained(
    fenced: FencedReset,
    operation: ForceResetOperation,
    stage: ForceResetStage,
    failure: ForceResetFailure,
    execution: ForceResetExecution,
): ForceResetOutcome {
    execution.observe(ResetObservation.Rejected(operation, stage, failure))
    return when (val storage = fenced.storage) {
        RetainedResetStorage.Missing -> ForceResetOutcome.Rejected(operation, stage, failure)
        is RetainedResetStorage.Held ->
            ForceResetOutcome.Retained(operation, stage, failure, storage.directory.toString())
    }
}
