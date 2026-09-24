package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import kotlinx.coroutines.delay

private const val MOVED_READ_PAUSE_MILLIS = 25L

/** Only repeatable semantic reads may be re-evaluated after a moving VFS epoch. */
enum class HostedReadReplayPolicy {
    SINGLE_EVALUATION,
    RETRY_MOVED_READ,
}

/** Discards an unpublishable read and retries under the caller's single enclosing host deadline. */
internal suspend fun <Value> retryMovedHostedRead(
    policy: HostedReadReplayPolicy,
    observation: IntellijReadObservation,
    onRetry: () -> Unit = {},
    pause: suspend () -> Unit = { delay(MOVED_READ_PAUSE_MILLIS) },
    attempt: suspend () -> HostedSemanticRead<Value>,
): HostedSemanticRead<Value> {
    while (true) {
        val result = attempt()
        if (
            policy != HostedReadReplayPolicy.RETRY_MOVED_READ ||
                result !is HostedSemanticRead.Rejected ||
                result.failure != HostedQueryFailure.Freshness(VfsPassiveReadAdmissionFailure.Moved)
        )
            return result
        observation.count(IntellijReadCounter.EPOCH_MOVED_RETRIES)
        onRetry()
        pause()
    }
}
