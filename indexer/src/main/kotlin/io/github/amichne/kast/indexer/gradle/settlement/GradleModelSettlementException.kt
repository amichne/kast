package io.github.amichne.kast.indexer.gradle.settlement

class GradleModelSettlementException(
    val outcome: GradleModelSettlementOutcome,
) : IllegalStateException(render(outcome)) {
    init {
        require(outcome !is GradleModelSettlementOutcome.Settled) {
            "a settled model must not be represented as a failure"
        }
    }

    private companion object {
        fun render(outcome: GradleModelSettlementOutcome): String {
            val failure =
                when (outcome) {
                    is GradleModelSettlementOutcome.Interrupted -> "Interrupted while waiting for Gradle model settlement"
                    is GradleModelSettlementOutcome.ProjectDisposed ->
                        "Project was disposed while waiting for Gradle model settlement"
                    is GradleModelSettlementOutcome.TimedOut -> "Timed out waiting for Gradle model settlement"
                    is GradleModelSettlementOutcome.Settled -> error("settled outcome is not a failure")
                }
            val evidence = outcome.evidence
            return "$failure: " +
                "lastObservation=${evidence.lastObservation}, " +
                "totalObservations=${evidence.totalObservations}, " +
                "totalTransitions=${evidence.totalTransitions}, " +
                "stableObservations=${evidence.stableObservations}, " +
                "elapsed=${evidence.elapsed}, " +
                "recentTransitions=${evidence.recentTransitions}"
        }
    }
}
