package io.github.amichne.kast.indexer.gradle.settlement

import java.time.Duration

data class GradleModelSettlementPolicy(
    val noProgressTimeout: Duration,
    val maximumWait: Duration,
    val observationInterval: Duration,
    val requiredStableObservations: Int,
    val maxTransitionTraceEntries: Int,
) {
    init {
        require(!noProgressTimeout.isNegative && !noProgressTimeout.isZero) {
            "noProgressTimeout must be positive"
        }
        require(maximumWait >= noProgressTimeout) {
            "maximumWait must be at least the no-progress timeout"
        }
        require(!observationInterval.isNegative && !observationInterval.isZero) {
            "observationInterval must be positive"
        }
        require(requiredStableObservations > 0) { "requiredStableObservations must be positive" }
        require(maxTransitionTraceEntries > 0) { "maxTransitionTraceEntries must be positive" }
    }

    companion object {
        @JvmStatic
        fun standard(): GradleModelSettlementPolicy =
            GradleModelSettlementPolicy(
                noProgressTimeout = Duration.ofMinutes(15),
                maximumWait = Duration.ofHours(1),
                observationInterval = Duration.ofMillis(100),
                requiredStableObservations = 10,
                maxTransitionTraceEntries = 64,
            )
    }
}
