package io.github.amichne.kast.headless

import java.time.Duration

data class HeadlessGradleModelSettlementPolicy(
    val timeout: Duration,
    val observationInterval: Duration,
    val requiredStableObservations: Int,
    val maxTransitionTraceEntries: Int,
) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        require(!observationInterval.isNegative && !observationInterval.isZero) {
            "observationInterval must be positive"
        }
        require(requiredStableObservations > 0) { "requiredStableObservations must be positive" }
        require(maxTransitionTraceEntries > 0) { "maxTransitionTraceEntries must be positive" }
    }

    companion object {
        @JvmStatic
        fun standard(): HeadlessGradleModelSettlementPolicy =
            HeadlessGradleModelSettlementPolicy(
                timeout = Duration.ofMinutes(5),
                observationInterval = Duration.ofMillis(100),
                requiredStableObservations = 10,
                maxTransitionTraceEntries = 64,
            )
    }
}
