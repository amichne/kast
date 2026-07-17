package io.github.amichne.kast.headless

import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class HeadlessGradleModelSettlementAwaiter internal constructor(
    private val policy: HeadlessGradleModelSettlementPolicy,
    private val nanoTime: () -> Long,
    private val pause: (Duration) -> Unit,
) {
    fun await(observer: HeadlessGradleImportObserver): HeadlessGradleModelSettlementOutcome {
        val startedAt = nanoTime()
        val transitions = ArrayDeque<HeadlessGradleImportTransition>()
        var lastObservation: HeadlessGradleImportObservation? = null
        var totalObservations = 0L
        var totalTransitions = 0L
        var stableObservations = 0

        while (true) {
            val elapsed = elapsedSince(startedAt)
            val observation = observer.observe()
            totalObservations += 1

            if (observation == lastObservation) {
                transitions.addLast(transitions.removeLast().repeatAt(elapsed))
            } else {
                if (lastObservation != null) {
                    totalTransitions += 1
                }
                transitions.addLast(
                    HeadlessGradleImportTransition(
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
                HeadlessGradleModelSettlementEvidence(
                    lastObservation = observation,
                    recentTransitions = transitions.toList(),
                    elapsed = elapsed,
                    totalObservations = totalObservations,
                    totalTransitions = totalTransitions,
                    stableObservations = stableObservations,
                )

            if (observation.lifecycle == HeadlessProjectLifecycleState.DISPOSED) {
                return HeadlessGradleModelSettlementOutcome.ProjectDisposed(evidence)
            }
            if (stableObservations >= policy.requiredStableObservations) {
                return HeadlessGradleModelSettlementOutcome.Settled(evidence)
            }
            if (elapsed >= policy.timeout) {
                return HeadlessGradleModelSettlementOutcome.TimedOut(evidence)
            }

            try {
                pause(policy.observationInterval)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return HeadlessGradleModelSettlementOutcome.Interrupted(evidence)
            }
        }
    }

    private fun elapsedSince(startedAt: Long): Duration =
        Duration.ofNanos((nanoTime() - startedAt).coerceAtLeast(0))

    companion object {
        @JvmStatic
        fun standard(): HeadlessGradleModelSettlementAwaiter =
            HeadlessGradleModelSettlementAwaiter(
                policy = HeadlessGradleModelSettlementPolicy.standard(),
                nanoTime = System::nanoTime,
                pause = { duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()) },
            )
    }
}
