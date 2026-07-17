package io.github.amichne.kast.headless

class HeadlessGradleModelSettlementException(
    val outcome: HeadlessGradleModelSettlementOutcome,
) : IllegalStateException(render(outcome)) {
    init {
        require(outcome !is HeadlessGradleModelSettlementOutcome.Settled) {
            "a settled model must not be represented as a failure"
        }
    }

    private companion object {
        fun render(outcome: HeadlessGradleModelSettlementOutcome): String {
            val failure =
                when (outcome) {
                    is HeadlessGradleModelSettlementOutcome.Interrupted -> "Interrupted while waiting for Gradle model settlement"
                    is HeadlessGradleModelSettlementOutcome.ProjectDisposed ->
                        "Project was disposed while waiting for Gradle model settlement"
                    is HeadlessGradleModelSettlementOutcome.TimedOut -> "Timed out waiting for Gradle model settlement"
                    is HeadlessGradleModelSettlementOutcome.Settled -> error("settled outcome is not a failure")
                }
            val evidence = outcome.evidence
            return "$failure: " +
                "lastObservation=${evidence.lastObservation}, " +
                "transitionProgress=${evidence.transitionProgress}, " +
                "totalObservations=${evidence.totalObservations}, " +
                "totalTransitions=${evidence.totalTransitions}, " +
                "stableObservations=${evidence.stableObservations}, " +
                "elapsed=${evidence.elapsed}, " +
                "recentTransitions=${evidence.recentTransitions}"
        }
    }
}
