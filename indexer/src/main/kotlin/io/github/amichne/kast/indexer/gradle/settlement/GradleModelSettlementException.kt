package io.github.amichne.kast.indexer.gradle.settlement

/**
 * Outer Java/IDE exception adapter for an already-typed settlement failure.
 *
 * Transition: `GradleModelSettlementOutcome.Failure -> GradleModelSettlementException`.
 * A settled outcome cannot be supplied by construction; Kotlin settlement code
 * continues to return the closed outcome rather than using this exception as a
 * failure protocol.
 */
class GradleModelSettlementException(
    val outcome: GradleModelSettlementOutcome.Failure,
) : IllegalStateException(render(outcome)) {
    private companion object {
        fun render(outcome: GradleModelSettlementOutcome.Failure): String {
            val failure =
                when (outcome) {
                    is GradleModelSettlementOutcome.Interrupted -> "Interrupted while waiting for Gradle model settlement"
                    is GradleModelSettlementOutcome.ProjectDisposed ->
                        "Project was disposed while waiting for Gradle model settlement"
                    is GradleModelSettlementOutcome.TimedOut -> "Timed out waiting for Gradle model settlement"
                }
            val evidence = outcome.evidence
            return "$failure: " +
                "lastObservation=${evidence.lastObservation}, " +
                "totalObservations=${evidence.totalObservations}, " +
                "totalTransitions=${evidence.totalTransitions}, " +
                "stableObservations=${evidence.stableObservations}, " +
                "elapsed=${evidence.elapsed}, " +
                "noProgress=${evidence.noProgress}, " +
                "recentTransitions=${evidence.recentTransitions}"
        }
    }
}
