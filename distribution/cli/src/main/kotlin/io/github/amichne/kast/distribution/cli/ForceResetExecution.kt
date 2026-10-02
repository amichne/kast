package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

internal data class ForceResetExecution(
    val runtime: ForceDaemonRuntime = NativeForceDaemonRuntime,
    val installer: ForceReinstaller = BundledForceReinstaller,
    val observe: (ResetObservation) -> Unit = { System.err.println(it.asJson()) },
)

internal fun forceResetInstallation(
    root: Path,
    home: Path,
    environment: Map<String, String>,
    operation: ForceResetOperation,
    execution: ForceResetExecution = ForceResetExecution(),
): ForceResetOutcome {
    val selected =
        when (val admitted = ForceResetRoot.admit(root, home)) {
            is ForceResetRootAdmission.Selected -> admitted.root
            ForceResetRootAdmission.Rejected ->
                return ForceResetOutcome.Rejected(operation, ForceResetStage.ADMISSION, ForceResetFailure.ROOT_REJECTED)
        }
    val fence =
        when (val acquired = ForceResetFence.acquire(selected)) {
            is ResetFenceAcquisition.Acquired -> acquired.fence
            is ResetFenceAcquisition.Rejected ->
                return ForceResetOutcome.Rejected(operation, ForceResetStage.FENCE, acquired.failure)
        }
    var released: ResetEffect
    val result =
        try {
            executeAcquired(fence, home, environment, operation, execution)
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
    environment: Map<String, String>,
    operation: ForceResetOperation,
    execution: ForceResetExecution,
): ForceResetOutcome {
    execution.observe(ResetObservation.Started(operation, ForceResetStage.FENCE))
    val fenced =
        when (val detached = FencedReset.detach(fence)) {
            is ResetFencing.Fenced -> detached.reset
            is ResetFencing.Rejected -> return rejected(operation, ForceResetStage.FENCE, detached.failure, execution)
        }
    execution.observe(ResetObservation.Completed(operation, ForceResetStage.FENCE))
    return executeFenced(fenced, home, environment, operation, execution)
}

private fun executeFenced(
    fenced: FencedReset,
    home: Path,
    environment: Map<String, String>,
    operation: ForceResetOperation,
    execution: ForceResetExecution,
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
    return when (operation) {
        ForceResetOperation.UNINSTALL -> finishRemoval(erased, home, execution)
        ForceResetOperation.REINSTALL -> stageReplacement(erased, home, environment, execution)
    }
}

private fun finishRemoval(erased: ErasedReset, home: Path, execution: ForceResetExecution): ForceResetOutcome {
    val operation = ForceResetOperation.UNINSTALL
    execution.observe(ResetObservation.Started(operation, ForceResetStage.FINAL_VERIFICATION))
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

private fun stageReplacement(
    erased: ErasedReset,
    home: Path,
    environment: Map<String, String>,
    execution: ForceResetExecution,
): ForceResetOutcome {
    val operation = ForceResetOperation.REINSTALL
    execution.observe(ResetObservation.Started(operation, ForceResetStage.INSTALLATION))
    val staged =
        when (val preparation = StagedReset.stage(erased, home, environment, execution.installer)) {
            is ResetStaging.Staged -> preparation.reset
            is ResetStaging.Rejected -> return compensate(erased, home, preparation.failure, execution)
        }
    execution.observe(ResetObservation.Completed(operation, ForceResetStage.INSTALLATION))
    execution.observe(ResetObservation.Started(operation, ForceResetStage.ACTIVATION))
    return when (val activation = ActiveReset.activate(staged, home, environment, execution.runtime)) {
        is ResetActivation.Active -> {
            execution.observe(ResetObservation.Completed(operation, ForceResetStage.ACTIVATION))
            val active = activation.reset
            ForceResetOutcome.Reinstalled(
                staged.payload.installation.toString(),
                staged.payload.version.value,
                staged.payload.command.toString(),
                active.generation.value,
            )
        }
        is ResetActivation.Rejected -> compensateStaged(staged, home, activation.failure, execution)
    }
}

private fun compensate(
    erased: ErasedReset,
    home: Path,
    failure: ForceResetFailure,
    execution: ForceResetExecution,
): ForceResetOutcome {
    execution.observe(ResetObservation.Rejected(ForceResetOperation.REINSTALL, ForceResetStage.INSTALLATION, failure))
    return when (val recovery = restoreStopped(erased, home, execution)) {
        ResetEffect.Completed ->
            ForceResetOutcome.Rejected(ForceResetOperation.REINSTALL, ForceResetStage.INSTALLATION, failure)
        is ResetEffect.Rejected ->
            ForceResetOutcome.RecoveryRequired(
                ForceResetOperation.REINSTALL,
                ForceResetStage.INSTALLATION,
                failure,
                recovery.failure,
                erased.root.toString(),
            )
    }
}

private fun compensateStaged(
    staged: StagedReset,
    home: Path,
    failure: ForceResetFailure,
    execution: ForceResetExecution,
): ForceResetOutcome {
    execution.observe(ResetObservation.Rejected(ForceResetOperation.REINSTALL, ForceResetStage.ACTIVATION, failure))
    return when (val recovery = restoreStopped(staged.erased, home, execution)) {
        ResetEffect.Completed ->
            ForceResetOutcome.Pending(
                staged.payload.installation.toString(),
                staged.payload.version.value,
                staged.payload.command.toString(),
                failure,
            )
        is ResetEffect.Rejected ->
            ForceResetOutcome.RecoveryRequired(
                ForceResetOperation.REINSTALL,
                ForceResetStage.ACTIVATION,
                failure,
                recovery.failure,
                staged.erased.root.toString(),
            )
    }
}

private fun restoreStopped(erased: ErasedReset, home: Path, execution: ForceResetExecution): ResetEffect {
    execution.observe(ResetObservation.Started(ForceResetOperation.REINSTALL, ForceResetStage.RECOVERY))
    val fenced = erased.fence.erased()
    // Attempt retirement even if restoring the filesystem barrier failed.
    val retired = ForceDaemonRetirement(execution.runtime).retire(erased.root, home)
    val outcome =
        when (fenced) {
            is ResetEffect.Rejected -> fenced
            ResetEffect.Completed -> retired
        }
    execution.observe(
        when (outcome) {
            ResetEffect.Completed -> ResetObservation.Completed(ForceResetOperation.REINSTALL, ForceResetStage.RECOVERY)
            is ResetEffect.Rejected ->
                ResetObservation.Rejected(ForceResetOperation.REINSTALL, ForceResetStage.RECOVERY, outcome.failure)
        }
    )
    return outcome
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
