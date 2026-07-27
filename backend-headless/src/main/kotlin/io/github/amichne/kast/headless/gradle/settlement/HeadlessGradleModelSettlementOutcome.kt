package io.github.amichne.kast.headless

sealed interface HeadlessGradleModelSettlementOutcome {
    val evidence: HeadlessGradleModelSettlementEvidence

    data class Settled(
        override val evidence: HeadlessGradleModelSettlementEvidence,
    ) : HeadlessGradleModelSettlementOutcome

    data class TimedOut(
        override val evidence: HeadlessGradleModelSettlementEvidence,
    ) : HeadlessGradleModelSettlementOutcome

    data class Interrupted(
        override val evidence: HeadlessGradleModelSettlementEvidence,
    ) : HeadlessGradleModelSettlementOutcome

    data class ProjectDisposed(
        override val evidence: HeadlessGradleModelSettlementEvidence,
    ) : HeadlessGradleModelSettlementOutcome
}
