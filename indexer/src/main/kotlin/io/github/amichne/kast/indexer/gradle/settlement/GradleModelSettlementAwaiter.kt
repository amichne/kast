package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.indexer.project.ProjectLifecycleState
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class GradleModelSettlementAwaiter internal constructor(
    private val policy: GradleModelSettlementPolicy,
    private val nanoTime: () -> Long,
    private val pause: (Duration) -> Unit,
) {
    fun await(observer: () -> GradleImportObservation): GradleModelSettlementOutcome {
        val startedAt = nanoTime()
        val transitions = ArrayDeque<GradleImportTransition>()
        var lastObservation: GradleImportObservation? = null
        var totalObservations = 0L
        var totalTransitions = 0L
        var stableObservations = 0

        while (true) {
            val elapsed = elapsedSince(startedAt)
            val observation = observer()
            totalObservations += 1

            if (observation == lastObservation) {
                transitions.addLast(transitions.removeLast().repeatAt(elapsed))
            } else {
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
                if (transitions.size > policy.maxTransitionTraceEntries) {
                    transitions.removeFirst()
                }
                lastObservation = observation
            }

            stableObservations = if (observation.isSettlementCandidate) stableObservations + 1 else 0
            val evidence =
                GradleModelSettlementEvidence(
                    lastObservation = observation,
                    recentTransitions = transitions.toList(),
                    elapsed = elapsed,
                    totalObservations = totalObservations,
                    totalTransitions = totalTransitions,
                    stableObservations = stableObservations,
                )

            if (observation.lifecycle == ProjectLifecycleState.DISPOSED) {
                return GradleModelSettlementOutcome.ProjectDisposed(evidence)
            }
            if (stableObservations >= policy.requiredStableObservations) {
                return GradleModelSettlementOutcome.Settled(evidence)
            }
            if (elapsed >= policy.timeout) {
                return GradleModelSettlementOutcome.TimedOut(evidence)
            }

            try {
                pause(policy.observationInterval)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return GradleModelSettlementOutcome.Interrupted(evidence)
            }
        }
    }

    private fun elapsedSince(startedAt: Long): Duration =
        Duration.ofNanos((nanoTime() - startedAt).coerceAtLeast(0))

    companion object {
        @JvmStatic
        fun standard(): GradleModelSettlementAwaiter =
            GradleModelSettlementAwaiter(
                policy = GradleModelSettlementPolicy.standard(),
                nanoTime = System::nanoTime,
                pause = { duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()) },
            )
    }
}
