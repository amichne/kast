package io.github.amichne.kast.headless

import java.time.Duration

data class HeadlessGradleModelSettlementEvidence(
    val lastObservation: HeadlessGradleImportObservation,
    val recentTransitions: List<HeadlessGradleImportTransition>,
    val elapsed: Duration,
    val totalObservations: Long,
    val totalTransitions: Long,
    val stableObservations: Int,
) {
    init {
        require(recentTransitions.isNotEmpty()) { "recentTransitions must retain the last observation" }
        require(recentTransitions.last().observation == lastObservation) {
            "the transition trace must retain the last observation"
        }
        require(!elapsed.isNegative) { "elapsed must not be negative" }
        require(totalObservations > 0) { "totalObservations must be positive" }
        require(totalTransitions >= 0) { "totalTransitions must not be negative" }
        require(stableObservations >= 0) { "stableObservations must not be negative" }
    }

    val transitionProgress: TransitionProgress
        get() =
            if (totalTransitions == 0L) {
                TransitionProgress.NO_TRANSITIONS
            } else {
                TransitionProgress.TRANSITIONS_OBSERVED
            }

    enum class TransitionProgress {
        NO_TRANSITIONS,
        TRANSITIONS_OBSERVED,
    }
}
