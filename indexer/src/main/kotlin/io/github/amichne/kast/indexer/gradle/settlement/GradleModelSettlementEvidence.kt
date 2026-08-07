package io.github.amichne.kast.indexer.gradle.settlement

import java.time.Duration

data class GradleModelSettlementEvidence(
    val lastObservation: GradleImportObservation,
    val recentTransitions: List<GradleImportTransition>,
    val elapsed: Duration,
    val totalObservations: Long,
    val totalTransitions: Long,
    val stableObservations: Int,
    val noProgress: Duration = Duration.ZERO,
) {
    init {
        require(recentTransitions.isNotEmpty()) { "recentTransitions must retain the last observation" }
        require(recentTransitions.last().observation == lastObservation) {
            "the transition trace must retain the last observation"
        }
        require(!elapsed.isNegative) { "elapsed must not be negative" }
        require(!noProgress.isNegative && noProgress <= elapsed) {
            "noProgress must be within elapsed"
        }
        require(totalObservations > 0) { "totalObservations must be positive" }
        require(totalTransitions >= 0) { "totalTransitions must not be negative" }
        require(stableObservations >= 0) { "stableObservations must not be negative" }
    }

}
