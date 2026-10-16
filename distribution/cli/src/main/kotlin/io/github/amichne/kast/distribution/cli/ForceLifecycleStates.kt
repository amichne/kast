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

    fun reserveCompletion(): ResetEffect =
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

/** A qualified staged payload completes without a daemon startup or readiness claim. */
internal class InstalledReset private constructor(val staged: StagedReset) {
    companion object {
        fun complete(staged: StagedReset): ResetInstallation {
            when (val reserved = staged.reserveCompletion()) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return ResetInstallation.Rejected(reserved.failure)
            }
            when (val admitted = staged.payload.payloadAdmission()) {
                ResetEffect.Completed -> Unit
                is ResetEffect.Rejected -> return ResetInstallation.Rejected(admitted.failure)
            }
            val installed = InstalledReset(staged)
            return when (val lifted = staged.erased.fence.liftInstalled(installed)) {
                ResetEffect.Completed -> ResetInstallation.Installed(installed)
                is ResetEffect.Rejected -> ResetInstallation.Rejected(lifted.failure)
            }
        }
    }
}

internal sealed interface ResetInstallation {
    data class Installed(val reset: InstalledReset) : ResetInstallation

    data class Rejected(val failure: ForceResetFailure) : ResetInstallation
}
