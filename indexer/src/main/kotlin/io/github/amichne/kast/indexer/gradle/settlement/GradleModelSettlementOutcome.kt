package io.github.amichne.kast.indexer.gradle.settlement

sealed interface GradleModelSettlementOutcome {
    val evidence: GradleModelSettlementEvidence

    sealed interface Failure : GradleModelSettlementOutcome

    data class Settled(
        override val evidence: GradleModelSettlementEvidence,
    ) : GradleModelSettlementOutcome

    data class TimedOut(
        override val evidence: GradleModelSettlementEvidence,
    ) : Failure

    data class Interrupted(
        override val evidence: GradleModelSettlementEvidence,
    ) : Failure

    data class ProjectDisposed(
        override val evidence: GradleModelSettlementEvidence,
    ) : Failure
}
