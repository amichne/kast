package io.github.amichne.kast.distribution.cli

import java.nio.file.Path

/** Capability constructors are private; each factory executes the prerequisite before minting its successor. */
internal class FencedReset
private constructor(
    val root: ForceResetRoot,
    val fence: ForceResetFence,
    val storage: RetainedResetStorage,
) {
    private var lease = ResetLease.FRESH

    fun reserveRetirement(): ResetEffect =
        when (lease) {
            ResetLease.FRESH -> {
                lease = ResetLease.CONSUMED
                ResetEffect.Completed
            }
            ResetLease.CONSUMED -> ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
        }

    companion object {
        fun detach(fence: ForceResetFence): ResetFencing {
            return when (val detached = fence.detach()) {
                is ResetStorageDetachment.Detached ->
                    ResetFencing.Fenced(FencedReset(fence.root, fence, detached.storage))
                is ResetStorageDetachment.Rejected -> ResetFencing.Rejected(detached.failure)
            }
        }
    }
}

internal sealed interface ResetFencing {
    data class Fenced(val reset: FencedReset) : ResetFencing

    data class Rejected(val failure: ForceResetFailure) : ResetFencing
}

internal class QuiescentReset private constructor(val fenced: FencedReset) {
    private var lease = ResetLease.FRESH

    fun consume(): ResetEffect =
        when (lease) {
            ResetLease.FRESH -> {
                lease = ResetLease.CONSUMED
                ResetEffect.Completed
            }
            ResetLease.CONSUMED -> ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
        }

    companion object {
        fun retire(fenced: FencedReset, home: Path, runtime: ForceDaemonRuntime): ResetRetirement {
            when (val reserved = fenced.reserveRetirement()) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return ResetRetirement.Rejected(reserved.failure)
            }
            return when (val result = ForceDaemonRetirement(runtime).retire(fenced.root.path, home, fenced.storage)) {
                ResetEffect.Completed -> ResetRetirement.Retired(QuiescentReset(fenced))
                is ResetEffect.Rejected -> ResetRetirement.Rejected(result.failure)
            }
        }
    }
}

internal sealed interface ResetRetirement {
    data class Retired(val reset: QuiescentReset) : ResetRetirement

    data class Rejected(val failure: ForceResetFailure) : ResetRetirement
}

internal class ErasedReset private constructor(val quiescent: QuiescentReset) {
    val root: Path
        get() = quiescent.fenced.root.path

    val fence: ForceResetFence
        get() = quiescent.fenced.fence

    fun stagingAdmission(): ResetEffect =
        if (java.nio.file.Files.notExists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) fence.admission()
        else ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)

    private var lease = ResetLease.FRESH

    fun reserveStage(): ResetEffect {
        if (lease != ResetLease.FRESH) return ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
        val admission = stagingAdmission()
        if (admission == ResetEffect.Completed) lease = ResetLease.CONSUMED
        return admission
    }

    companion object {
        fun erase(quiescent: QuiescentReset): ResetErasure {
            when (val reserved = quiescent.consume()) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return ResetErasure.Rejected(reserved.failure)
            }
            return when (val deletion = quiescent.fenced.root.erase(quiescent)) {
                ForceResetDeletion.Removed ->
                    when (val recorded = quiescent.fenced.fence.erased()) {
                        ResetEffect.Completed -> ResetErasure.Erased(ErasedReset(quiescent))
                        is ResetEffect.Rejected -> ResetErasure.Rejected(recorded.failure)
                    }
                is ForceResetDeletion.Rejected -> ResetErasure.Rejected(deletion.failure)
            }
        }
    }
}

internal sealed interface ResetErasure {
    data class Erased(val reset: ErasedReset) : ResetErasure

    data class Rejected(val failure: ForceResetFailure) : ResetErasure
}

internal class StagedReset private constructor(val erased: ErasedReset, val payload: FreshResetInstallation) {
    private var lease = ResetLease.FRESH

    fun reserveActivation(): ResetEffect =
        when (lease) {
            ResetLease.FRESH -> {
                lease = ResetLease.CONSUMED
                ResetEffect.Completed
            }
            ResetLease.CONSUMED -> ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
        }

    companion object {
        fun stage(
            erased: ErasedReset,
            home: Path,
            environment: Map<String, String>,
            installer: ForceReinstaller,
        ): ResetStaging {
            when (val admission = erased.reserveStage()) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return ResetStaging.Rejected(admission.failure)
            }
            return when (val result = installer.stage(erased, home, environment)) {
                is FreshResetResult.Prepared ->
                    if (
                        result.installation.installation == erased.root.resolve("installation") &&
                            result.installation.command == erased.root.resolve("bin/kast")
                    )
                        ResetStaging.Staged(StagedReset(erased, result.installation))
                    else ResetStaging.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED)
                is FreshResetResult.Rejected -> ResetStaging.Rejected(result.failure)
            }
        }
    }
}

internal sealed interface ResetStaging {
    data class Staged(val reset: StagedReset) : ResetStaging

    data class Rejected(val failure: ForceResetFailure) : ResetStaging
}

internal class ActiveReset private constructor(val staged: StagedReset, val generation: RuntimeServiceGeneration) {
    companion object {
        fun activate(
            staged: StagedReset,
            home: Path,
            environment: Map<String, String>,
            runtime: ForceDaemonRuntime,
        ): ResetActivation {
            when (val reserved = staged.reserveActivation()) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return ResetActivation.Rejected(reserved.failure)
            }
            if (staged.payload.serviceAdmission() != ResetEffect.Completed)
                return ResetActivation.Rejected(ForceResetFailure.INSTALLATION_UNVERIFIED)
            when (val released = staged.erased.fence.beginActivation(staged)) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return ResetActivation.Rejected(released.failure)
            }
            val started = runtime.activate(staged.payload.installation, home, environment)
            if (started !is LifecycleChildObservation.Exited || started.code != 0)
                return ResetActivation.Rejected(ForceResetFailure.ACTIVATION_REJECTED)
            return when (val readiness = ReadyReset.observe(staged, runtime)) {
                is ResetReadiness.Ready -> complete(readiness.reset)
                is ResetReadiness.Rejected -> ResetActivation.Rejected(readiness.failure)
            }
        }

        private fun complete(ready: ReadyReset): ResetActivation =
            when (val released = ready.staged.erased.fence.liftReady(ready)) {
                ResetEffect.Completed -> ResetActivation.Active(ActiveReset(ready.staged, ready.generation))
                is ResetEffect.Rejected -> ResetActivation.Rejected(released.failure)
            }
    }
}

internal sealed interface ResetActivation {
    data class Active(val reset: ActiveReset) : ResetActivation

    data class Rejected(val failure: ForceResetFailure) : ResetActivation
}
