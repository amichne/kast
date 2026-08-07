package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.indexer.project.ProjectLifecycleState
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class GradleModelSettlementAwaiter internal constructor(
    private val policy: GradleModelSettlementPolicy,
    private val clock: MonotonicClock,
    private val pause: (Duration) -> Unit,
) {
    fun await(observer: () -> GradleImportObservation): GradleModelSettlementOutcome {
        val startedAt = clock.now()
        var lastProgressAt = startedAt
        val transitions = ArrayDeque<GradleImportTransition>()
        var lastObservation: GradleImportObservation? = null
        var totalObservations = 0L
        var totalTransitions = 0L
        var stableObservations = 0

        while (true) {
            val observedAt = clock.now()
            val elapsed = observedAt.elapsedSince(startedAt)
            val observation = observer()
            totalObservations += 1

            if (observation == lastObservation) {
                transitions.addLast(transitions.removeLast().repeatAt(elapsed))
            } else {
                lastProgressAt = observedAt
                if (lastObservation != null) {
                    totalTransitions += 1
                }
                transitions.addLast(
                    GradleImportTransition(
                        observation = observation,
                        firstObservedAt = elapsed,
                        lastObservedAt = elapsed,
                        occurrenceCount = 1,
                    ),
                )
                if (transitions.size > policy.maxTransitionTraceEntries.value) {
                    transitions.removeFirst()
                }
                lastObservation = observation
            }

            stableObservations = if (observation.isSettlementCandidate) stableObservations + 1 else 0
            if (observation.isSettlementCandidate) {
                lastProgressAt = observedAt
            }
            val noProgress = observedAt.elapsedSince(lastProgressAt)
            val evidence =
                GradleModelSettlementEvidence(
                    lastObservation = observation,
                    recentTransitions = transitions.toList(),
                    elapsed = elapsed,
                    noProgress = noProgress,
                    totalObservations = totalObservations,
                    totalTransitions = totalTransitions,
                    stableObservations = stableObservations,
                )

            if (observation.lifecycle == ProjectLifecycleState.DISPOSED) {
                return GradleModelSettlementOutcome.ProjectDisposed(evidence)
            }
            if (stableObservations >= policy.requiredStableObservations.value) {
                return GradleModelSettlementOutcome.Settled(evidence)
            }
            if (
                noProgress >= policy.progressWaitPolicy.noProgressTimeout ||
                elapsed >= policy.progressWaitPolicy.maximumWait
            ) {
                return GradleModelSettlementOutcome.TimedOut(evidence)
            }

            try {
                pause(policy.progressWaitPolicy.observationInterval)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return GradleModelSettlementOutcome.Interrupted(evidence)
            }
        }
    }

    companion object {
        @JvmStatic
        fun standard(): GradleModelSettlementAwaiter =
            GradleModelSettlementAwaiter(
                policy = GradleModelSettlementPolicy.standard(),
                clock = MonotonicClock.system(),
                pause = { duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()) },
            )
    }
}
