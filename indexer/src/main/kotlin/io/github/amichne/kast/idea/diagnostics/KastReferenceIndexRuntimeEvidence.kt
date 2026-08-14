package io.github.amichne.kast.idea.diagnostics

import io.github.amichne.kast.api.contract.CapabilityLaneBlocker
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.ReferenceCoverage
import io.github.amichne.kast.api.contract.ReferenceCoverageLimitation
import io.github.amichne.kast.api.contract.RetainedCapabilityLaneFallback
import io.github.amichne.kast.api.contract.RetainedCapabilityLaneReadiness
import io.github.amichne.kast.api.contract.RuntimeProgressStage
import io.github.amichne.kast.api.contract.RuntimeProgressTiming
import io.github.amichne.kast.api.contract.RuntimeProgressWork
import io.github.amichne.kast.api.contract.RuntimeReadinessProgress
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import java.time.Instant

/**
 * Proof transition:
 * `(RuntimeStatusResponse, KastSourceIndexSummary) -> RuntimeStatusResponse`.
 *
 * Refines one diagnostics observation into an authoritative retained-reference
 * lane and matching legacy coverage in a single DTO construction. The summary
 * cannot manufacture an evidence revision; ready or degraded observations may
 * only retain evidence already carried by [RuntimeStatusResponse.readiness].
 * Raw wall-clock observation is permitted only at this diagnostics boundary.
 */
internal fun RuntimeStatusResponse.withReferenceIndex(
    index: KastSourceIndexSummary,
): RuntimeStatusResponse {
    val evidence = ReferenceIndexRuntimeEvidence.derive(
        currentLane = readiness.referencesLane,
        index = index,
        observedAt = Instant.now(),
    )
    return copy(
        readiness = readiness.copy(referencesLane = evidence.lane),
        referenceCoverageState = evidence.coverage.state,
        referenceCoverageLimitations = evidence.coverage.limitations,
    )
}

private class ReferenceIndexRuntimeEvidence(
    val lane: RetainedCapabilityLaneReadiness,
    val coverage: ReferenceCoverage,
) {
    companion object {
        /**
         * Proof transition:
         * `(RetainedCapabilityLaneReadiness, KastSourceIndexSummary, Instant) -> ReferenceIndexRuntimeEvidence`.
         *
         * Establishes a retained lane and coverage pair that agree by
         * construction. Expected lifecycle absence remains finite lane and
         * coverage data; only a previously proven revision can remain ready.
         */
        fun derive(
            currentLane: RetainedCapabilityLaneReadiness,
            index: KastSourceIndexSummary,
            observedAt: Instant,
        ): ReferenceIndexRuntimeEvidence = when (index.state) {
            KastIndexState.READY -> currentLane.readyEvidence(index)
            KastIndexState.DEGRADED -> currentLane.degradedEvidence(index)
            KastIndexState.INDEXING -> ReferenceIndexRuntimeEvidence(
                lane = RetainedCapabilityLaneReadiness.Building(
                    progress = RuntimeReadinessProgress.derive(
                        stage = index.runtimeProgressStage(),
                        work = index.runtimeProgressWork(),
                        timing = index.runtimeProgressTiming(observedAt),
                    ),
                    fallback = (currentLane as? RetainedCapabilityLaneReadiness.Building)?.fallback
                        ?: RetainedCapabilityLaneFallback.None,
                ),
                coverage = ReferenceCoverage.qualified(
                    limitations = index.limitationsOr(
                        ReferenceCoverageLimitation.INDEXING_IN_PROGRESS,
                    ),
                    indexReady = false,
                ),
            )
            KastIndexState.FAILED -> ReferenceIndexRuntimeEvidence(
                lane = RetainedCapabilityLaneReadiness.Blocked(
                    CapabilityLaneBlocker.INITIALIZATION_FAILED,
                ),
                coverage = ReferenceCoverage.incomplete(
                    index.limitationsOr(ReferenceCoverageLimitation.CRITICAL_STAGE_GAP),
                ),
            )
            KastIndexState.WAITING_FOR_IDE,
            KastIndexState.HYDRATING,
                -> ReferenceIndexRuntimeEvidence(
                    lane = RetainedCapabilityLaneReadiness.Blocked(
                        CapabilityLaneBlocker.DEPENDENCY_UNAVAILABLE,
                    ),
                    coverage = ReferenceCoverage.unavailable(
                        index.limitationsOr(ReferenceCoverageLimitation.PROJECT_MODEL_UNAVAILABLE),
                    ),
                )
            KastIndexState.CANCELLED -> ReferenceIndexRuntimeEvidence(
                lane = RetainedCapabilityLaneReadiness.Blocked(
                    CapabilityLaneBlocker.CAPABILITY_UNAVAILABLE,
                ),
                coverage = ReferenceCoverage.unavailable(
                    index.limitationsOr(ReferenceCoverageLimitation.CANCELLED),
                ),
            )
            KastIndexState.IDLE -> ReferenceIndexRuntimeEvidence(
                lane = RetainedCapabilityLaneReadiness.Blocked(
                    CapabilityLaneBlocker.CAPABILITY_UNAVAILABLE,
                ),
                coverage = ReferenceCoverage.unavailable(
                    index.limitationsOr(ReferenceCoverageLimitation.INDEX_NOT_COMMITTED),
                ),
            )
        }
    }
}

private fun RetainedCapabilityLaneReadiness.readyEvidence(
    index: KastSourceIndexSummary,
): ReferenceIndexRuntimeEvidence = when (this) {
    is RetainedCapabilityLaneReadiness.Available -> ReferenceIndexRuntimeEvidence(
        lane = this,
        coverage = ReferenceCoverage.complete(index.referenceCoverageLimitations),
    )
    is RetainedCapabilityLaneReadiness.Building -> ReferenceIndexRuntimeEvidence(
        lane = this,
        coverage = ReferenceCoverage.qualified(
            limitations = listOf(ReferenceCoverageLimitation.INDEXING_IN_PROGRESS),
            indexReady = false,
        ),
    )
    is RetainedCapabilityLaneReadiness.Blocked -> ReferenceIndexRuntimeEvidence(
        lane = this,
        coverage = ReferenceCoverage.unavailable(
            listOf(ReferenceCoverageLimitation.INDEX_NOT_COMMITTED),
        ),
    )
}

private fun RetainedCapabilityLaneReadiness.degradedEvidence(
    index: KastSourceIndexSummary,
): ReferenceIndexRuntimeEvidence = when (this) {
    is RetainedCapabilityLaneReadiness.Available -> ReferenceIndexRuntimeEvidence(
        lane = this,
        coverage = ReferenceCoverage.qualified(
            limitations = index.limitationsOr(ReferenceCoverageLimitation.NONCRITICAL_STAGE_GAP),
            indexReady = true,
        ),
    )
    is RetainedCapabilityLaneReadiness.Building -> ReferenceIndexRuntimeEvidence(
        lane = this,
        coverage = ReferenceCoverage.qualified(
            limitations = listOf(ReferenceCoverageLimitation.INDEXING_IN_PROGRESS),
            indexReady = false,
        ),
    )
    is RetainedCapabilityLaneReadiness.Blocked -> ReferenceIndexRuntimeEvidence(
        lane = this,
        coverage = ReferenceCoverage.unavailable(
            listOf(ReferenceCoverageLimitation.INDEX_NOT_COMMITTED),
        ),
    )
}

private fun KastSourceIndexSummary.limitationsOr(
    default: ReferenceCoverageLimitation,
): List<ReferenceCoverageLimitation> = referenceCoverageLimitations.ifEmpty { listOf(default) }

/**
 * Proof transition: `KastSourceIndexSummary -> RuntimeProgressStage`.
 *
 * Maps the closed diagnostics lifecycle to the corresponding public progress
 * stage; no string or ordinal protocol crosses the status boundary.
 */
private fun KastSourceIndexSummary.runtimeProgressStage(): RuntimeProgressStage = when (state) {
    KastIndexState.WAITING_FOR_IDE -> RuntimeProgressStage.IDE_INDEXING
    KastIndexState.HYDRATING -> RuntimeProgressStage.MODEL_SETTLEMENT
    else -> RuntimeProgressStage.REFERENCE_INDEX
}

/**
 * Proof transition: `KastSourceIndexSummary -> RuntimeProgressWork`.
 *
 * Refines the diagnostics DTO's optional file count at this UI boundary into
 * closed uncounted or bounded work before readiness code consumes it.
 */
private fun KastSourceIndexSummary.runtimeProgressWork(): RuntimeProgressWork = fileCount
    ?.let(::NonNegativeInt)
    ?.let(RuntimeProgressWork::pending)
    ?: RuntimeProgressWork.Uncounted

/**
 * Proof transition: `(KastSourceIndexSummary, Instant) -> RuntimeProgressTiming`.
 *
 * Refines closed diagnostics timing state into bounded readiness timing. The
 * current wall-clock observation is accepted only at this status boundary.
 */
private fun KastSourceIndexSummary.runtimeProgressTiming(observedAt: Instant): RuntimeProgressTiming =
    when (val timing = progressTiming) {
        KastIndexProgressTiming.Unobserved -> RuntimeProgressTiming.unobserved()
        is KastIndexProgressTiming.Observed -> timing.runtimeTimingAt(observedAt)
    }
