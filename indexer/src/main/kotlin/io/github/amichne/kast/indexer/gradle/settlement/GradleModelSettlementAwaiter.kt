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
        var lastProgressAt = startedAt
        val transitions = ArrayDeque<GradleImportTransition>()
        var lastObservation: GradleImportObservation? = null
        var totalObservations = 0L
        var totalTransitions = 0L
        var stableObservations = 0

        while (true) {
            val observedAt = nanoTime()
            val elapsed = durationBetween(startedAt, observedAt)
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
                if (transitions.size > policy.maxTransitionTraceEntries) {
                    transitions.removeFirst()
                }
                lastObservation = observation
            }

            stableObservations = if (observation.isSettlementCandidate) stableObservations + 1 else 0
            if (observation.isSettlementCandidate) {
                lastProgressAt = observedAt
            }
            val noProgress = durationBetween(lastProgressAt, observedAt)
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
            if (stableObservations >= policy.requiredStableObservations) {
                return GradleModelSettlementOutcome.Settled(evidence)
            }
            if (noProgress >= policy.noProgressTimeout || elapsed >= policy.maximumWait) {
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

    private fun durationBetween(startedAt: Long, observedAt: Long): Duration =
        Duration.ofNanos((observedAt - startedAt).coerceAtLeast(0))

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
