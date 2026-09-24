package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import kotlinx.coroutines.delay

/** One current epoch and its passive freshness capability, retained through model capture. */
internal data class HostedEpochAdmission(
    val epoch: ProjectReadEpoch<*>,
    val freshness: VfsPassiveReadCapability,
)

private sealed interface HostedEpochAttempt {
    data class Ready(val admission: HostedEpochAdmission) : HostedEpochAttempt

    data class Rejected(val failure: HostedQueryFailure) : HostedEpochAttempt

    data class Retry(val counter: IntellijReadCounter) : HostedEpochAttempt
}

/** Waits for write preemption or a moving epoch within the enclosing host deadline. */
internal suspend fun awaitHostedEpochAdmission(
    observe: () -> ProjectReadEpochObservation,
    admit: (ProjectReadEpoch<*>) -> VfsPassiveReadAdmission,
    observation: IntellijReadObservation = IntellijReadObservation.None,
    pause: suspend () -> Unit = { delay(READ_PREEMPTION_PAUSE_MILLIS) },
): Refinement<HostedEpochAdmission, HostedQueryFailure> {
    while (true) {
        when (val attempt = attemptHostedEpochAdmission(observe, admit)) {
            is HostedEpochAttempt.Ready -> return Refinement.Refined(attempt.admission)
            is HostedEpochAttempt.Rejected -> return Refinement.Rejected(attempt.failure)
            is HostedEpochAttempt.Retry -> {
                observation.count(attempt.counter)
                pause()
            }
        }
    }
}

private fun attemptHostedEpochAdmission(
    observe: () -> ProjectReadEpochObservation,
    admit: (ProjectReadEpoch<*>) -> VfsPassiveReadAdmission,
): HostedEpochAttempt {
    val epoch =
        when (val observed = observe()) {
            is ProjectReadEpochObservation.Observed -> observed.epoch
            is ProjectReadEpochObservation.Rejected ->
                return if (observed.failure == ProjectReadEpochObservationFailure.ReadPreempted)
                    HostedEpochAttempt.Retry(IntellijReadCounter.EPOCH_READ_PREEMPTIONS)
                else HostedEpochAttempt.Rejected(HostedQueryFailure.ReadEpoch(observed.failure))
        }
    return when (val current = admit(epoch)) {
        is VfsPassiveReadAdmission.Admitted -> HostedEpochAttempt.Ready(HostedEpochAdmission(epoch, current.capability))
        is VfsPassiveReadAdmission.Rejected ->
            if (current.failure.canRetry())
                HostedEpochAttempt.Retry(
                    if (current.failure == VfsPassiveReadAdmissionFailure.Moved) IntellijReadCounter.EPOCH_MOVED_RETRIES
                    else IntellijReadCounter.EPOCH_READ_PREEMPTIONS
                )
            else HostedEpochAttempt.Rejected(HostedQueryFailure.Freshness(current.failure))
    }
}

private const val READ_PREEMPTION_PAUSE_MILLIS = 25L

private fun VfsPassiveReadAdmissionFailure.canRetry(): Boolean =
    this == VfsPassiveReadAdmissionFailure.Moved ||
        (this is VfsPassiveReadAdmissionFailure.Unavailable && cause == VfsPassiveReadUnavailableCause.ReadPreempted)
