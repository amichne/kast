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
