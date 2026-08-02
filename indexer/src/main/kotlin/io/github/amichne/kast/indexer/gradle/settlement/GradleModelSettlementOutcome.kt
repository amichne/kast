package io.github.amichne.kast.indexer.gradle.settlement

sealed interface GradleModelSettlementOutcome {
    val evidence: GradleModelSettlementEvidence

    data class Settled(
        override val evidence: GradleModelSettlementEvidence,
    ) : GradleModelSettlementOutcome

    data class TimedOut(
        override val evidence: GradleModelSettlementEvidence,
    ) : GradleModelSettlementOutcome

    data class Interrupted(
        override val evidence: GradleModelSettlementEvidence,
    ) : GradleModelSettlementOutcome

    data class ProjectDisposed(
        override val evidence: GradleModelSettlementEvidence,
    ) : GradleModelSettlementOutcome
}
