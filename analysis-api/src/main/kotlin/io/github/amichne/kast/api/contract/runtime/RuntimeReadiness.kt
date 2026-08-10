package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
data class RuntimeReadiness(
    @DocField(description = "Readiness of the runtime process and workspace session.")
    val runtime: RuntimeReadinessLane,
    @DocField(description = "Readiness of the imported Gradle project model.")
    val model: RuntimeReadinessLane,
    @DocField(description = "Readiness of committed symbol-reference evidence.")
    val references: RuntimeReadinessLane,
    @DocField(description = "Readiness of committed semantic graph evidence.")
    val semanticGraph: RuntimeReadinessLane,
    @DocField(description = "Readiness of verified mutation operations.")
    val mutation: RuntimeReadinessLane,
) {
    val summary: RuntimeReadinessSummary
        get() = RuntimeReadinessSummary.derive(this)

    companion object {
        fun ready(): RuntimeReadiness = RuntimeReadiness(
            runtime = RuntimeReadinessLane.Ready,
            model = RuntimeReadinessLane.Ready,
            references = RuntimeReadinessLane.Ready,
            semanticGraph = RuntimeReadinessLane.Ready,
            mutation = RuntimeReadinessLane.Ready,
        )
    }
}

sealed interface RuntimeReadinessSummary {
    data object Ready : RuntimeReadinessSummary

    data object NotReady : RuntimeReadinessSummary

    companion object {
        /**
         * Proof transition: `RuntimeReadiness -> RuntimeReadinessSummary`.
         *
         * Collapses the five typed lanes into a closed exhaustive summary;
         * callers retain the summary as proof instead of passing a Boolean.
         */
        fun derive(readiness: RuntimeReadiness): RuntimeReadinessSummary = if (
            readiness.runtime is RuntimeReadinessLane.Ready &&
            readiness.model is RuntimeReadinessLane.Ready &&
            readiness.references is RuntimeReadinessLane.Ready &&
            readiness.semanticGraph is RuntimeReadinessLane.Ready &&
            readiness.mutation is RuntimeReadinessLane.Ready
        ) {
            Ready
        } else {
            NotReady
        }
    }
}

@Serializable
sealed interface RuntimeReadinessLane {
    @Serializable
    @SerialName("READY")
    data object Ready : RuntimeReadinessLane

    @Serializable
    @SerialName("IN_PROGRESS")
    data class InProgress(
        @DocField(description = "Typed progress evidence for the active runtime stage.")
        val progress: RuntimeReadinessProgress,
    ) : RuntimeReadinessLane

    @Serializable
    @SerialName("BLOCKED")
    data object Blocked : RuntimeReadinessLane

    companion object {
        fun inProgress(stage: RuntimeProgressStage): InProgress =
            InProgress(RuntimeReadinessProgress.uncounted(stage))

        /**
         * Proof transition:
         * `(RuntimeReadinessLane, ReferenceCoverage) -> ReferenceReadinessAlignmentResolution`.
         *
         * A resolved value carries the agreement between layered readiness and
         * persisted reference coverage. Mismatch is finite
         * [ReferenceReadinessAlignmentFailure] data; callers retain the aligned
         * proof rather than discarding a Boolean validation result. In-progress
         * agreement requires the reference stage while allowing work and timing
         * observations to vary.
         */
        internal fun alignWithReferenceCoverage(
            lane: RuntimeReadinessLane,
            coverage: ReferenceCoverage,
        ): ReferenceReadinessAlignmentResolution {
            val expected = fromReferenceCoverage(coverage)
            val aligned = when (expected) {
                Ready -> lane is Ready
                is InProgress -> lane is InProgress && lane.progress.stage == expected.progress.stage
                Blocked -> lane is Blocked
            }
            return if (aligned) {
                ReferenceReadinessAlignmentResolution.Aligned(
                    ReferenceReadinessAlignment(lane, coverage),
                )
            } else {
                ReferenceReadinessAlignmentResolution.Rejected(
                    ReferenceReadinessAlignmentFailure.Mismatch(expected, lane),
                )
            }
        }

        internal fun fromReferenceCoverage(coverage: ReferenceCoverage): RuntimeReadinessLane = when {
            coverage.indexReady -> Ready
            coverage.state == ReferenceCoverageState.QUALIFIED -> inProgress(RuntimeProgressStage.REFERENCE_INDEX)
            else -> Blocked
        }
    }
}

internal data class ReferenceReadinessAlignment(
    val lane: RuntimeReadinessLane,
    val coverage: ReferenceCoverage,
)

internal sealed interface ReferenceReadinessAlignmentFailure {
    data class Mismatch(
        val expected: RuntimeReadinessLane,
        val actual: RuntimeReadinessLane,
    ) : ReferenceReadinessAlignmentFailure
}

internal sealed interface ReferenceReadinessAlignmentResolution {
    data class Aligned(
        val proof: ReferenceReadinessAlignment,
    ) : ReferenceReadinessAlignmentResolution

    data class Rejected(
        val failure: ReferenceReadinessAlignmentFailure,
    ) : ReferenceReadinessAlignmentResolution
}

@ConsistentCopyVisibility
internal data class RuntimeStatusConsistency private constructor(
    val referenceAlignment: ReferenceReadinessAlignment,
    val summary: RuntimeReadinessSummary,
) {
    companion object {
        /**
         * Proof transition:
         * `(RuntimeState, RuntimeReadiness, ReferenceCoverage) -> RuntimeStatusConsistencyResolution`.
         *
         * Establishes that layered readiness agrees with both legacy reference
         * coverage. Mismatch is finite [RuntimeStatusConsistencyFailure] data.
         */
        fun resolve(
            state: RuntimeState,
            readiness: RuntimeReadiness,
            referenceCoverage: ReferenceCoverage,
        ): RuntimeStatusConsistencyResolution {
            val stateAligned = when (state) {
                RuntimeState.STARTING -> readiness.runtime is RuntimeReadinessLane.InProgress
                RuntimeState.INDEXING -> readiness.runtime is RuntimeReadinessLane.Ready &&
                    readiness.model is RuntimeReadinessLane.InProgress
                RuntimeState.READY -> readiness.runtime is RuntimeReadinessLane.Ready &&
                    readiness.model is RuntimeReadinessLane.Ready
                RuntimeState.DEGRADED -> readiness.runtime is RuntimeReadinessLane.Blocked ||
                    readiness.model is RuntimeReadinessLane.Blocked
            }
            if (!stateAligned) {
                return RuntimeStatusConsistencyResolution.Rejected(
                    RuntimeStatusConsistencyFailure.StateMismatch(state, readiness.runtime, readiness.model),
                )
            }
            val referenceAlignment = when (
                val resolution = RuntimeReadinessLane.alignWithReferenceCoverage(
                    readiness.references,
                    referenceCoverage,
                )
            ) {
                is ReferenceReadinessAlignmentResolution.Aligned -> resolution.proof
                is ReferenceReadinessAlignmentResolution.Rejected -> {
                    return RuntimeStatusConsistencyResolution.Rejected(
                        RuntimeStatusConsistencyFailure.ReferenceCoverageMismatch(resolution.failure),
                    )
                }
            }
            val summary = readiness.summary
            return RuntimeStatusConsistencyResolution.Verified(
                RuntimeStatusConsistency(referenceAlignment, summary),
            )
        }
    }
}

internal sealed interface RuntimeStatusConsistencyFailure {
    data class StateMismatch(
        val state: RuntimeState,
        val runtime: RuntimeReadinessLane,
        val model: RuntimeReadinessLane,
    ) : RuntimeStatusConsistencyFailure
    data class ReferenceCoverageMismatch(
        val failure: ReferenceReadinessAlignmentFailure,
    ) : RuntimeStatusConsistencyFailure

}

internal sealed interface RuntimeStatusConsistencyResolution {
    data class Verified(
        val proof: RuntimeStatusConsistency,
    ) : RuntimeStatusConsistencyResolution

    data class Rejected(
        val failure: RuntimeStatusConsistencyFailure,
    ) : RuntimeStatusConsistencyResolution
}

sealed interface RuntimeProgressWork {
    data object Uncounted : RuntimeProgressWork

    @ConsistentCopyVisibility
    data class Pending private constructor(
        val totalUnits: PositiveInt,
    ) : RuntimeProgressWork {
        companion object {
            internal fun withTotal(totalUnits: PositiveInt): Pending = Pending(totalUnits)
        }
    }

    companion object {
        /**
         * Proof transition: `NonNegativeInt -> RuntimeProgressWork`.
         *
         * Derives either explicit uncounted work or pending work with a positive
         * total. Zero never masquerades as a counted workload.
         */
        fun pending(totalUnits: NonNegativeInt): RuntimeProgressWork = if (totalUnits.value == 0) {
            Uncounted
        } else {
            Pending.withTotal(PositiveInt(totalUnits.value))
        }
    }
}

@ConsistentCopyVisibility
data class RuntimeProgressTiming private constructor(
    val elapsed: Duration,
    val noProgress: Duration,
) {
    companion object {
        fun unobserved(): RuntimeProgressTiming = RuntimeProgressTiming(Duration.ZERO, Duration.ZERO)

        /**
         * Proof transition:
         * `(Instant, Instant, Instant) -> RuntimeProgressTiming`.
         *
         * Derives non-negative elapsed and no-progress durations with
         * `noProgress <= elapsed`. Wall-clock regression is normalized closed at
         * this clock boundary; raw epoch values never enter readiness logic.
         */
        fun between(
            stageStartedAt: Instant,
            lastProgressAt: Instant,
            observedAt: Instant,
        ): RuntimeProgressTiming {
            val effectiveObservedAt = observedAt.coerceAtLeast(stageStartedAt)
            val effectiveProgressAt = lastProgressAt
                .coerceAtLeast(stageStartedAt)
                .coerceAtMost(effectiveObservedAt)
            return RuntimeProgressTiming(
                elapsed = Duration.between(stageStartedAt, effectiveObservedAt),
                noProgress = Duration.between(effectiveProgressAt, effectiveObservedAt),
            )
        }

        private fun Instant.coerceAtLeast(minimum: Instant): Instant = if (isBefore(minimum)) minimum else this

        private fun Instant.coerceAtMost(maximum: Instant): Instant = if (isAfter(maximum)) maximum else this
    }
}

@Serializable
@ConsistentCopyVisibility
data class RuntimeReadinessProgress private constructor(
    @DocField(description = "Current progress stage.")
    val stage: RuntimeProgressStage,
    @DocField(description = "Completed work units. Zero with zero total means uncounted work.")
    val completedUnits: Long,
    @DocField(description = "Total known work units. Zero means the stage is not countable.")
    val totalUnits: Long,
    @DocField(description = "Milliseconds elapsed in the current stage.")
    val elapsedMillis: Long,
    @DocField(description = "Milliseconds since the most recent observed progress.")
    val noProgressMillis: Long,
) {
    companion object {
        fun uncounted(stage: RuntimeProgressStage): RuntimeReadinessProgress = derive(
            stage = stage,
            work = RuntimeProgressWork.Uncounted,
            timing = RuntimeProgressTiming.unobserved(),
        )

        /**
         * Proof transition:
         * `(RuntimeProgressStage, RuntimeProgressWork, RuntimeProgressTiming) -> RuntimeReadinessProgress`.
         *
         * Preserves closed work cardinality and bounded timing evidence in the
         * wire DTO. Primitive fields are extracted only for protocol
         * serialization; core callers construct progress from the typed inputs.
         */
        fun derive(
            stage: RuntimeProgressStage,
            work: RuntimeProgressWork,
            timing: RuntimeProgressTiming,
        ): RuntimeReadinessProgress {
            val totalUnits = when (work) {
                RuntimeProgressWork.Uncounted -> 0L
                is RuntimeProgressWork.Pending -> work.totalUnits.value.toLong()
            }
            return RuntimeReadinessProgress(
                stage = stage,
                completedUnits = 0,
                totalUnits = totalUnits,
                elapsedMillis = timing.elapsed.toMillis(),
                noProgressMillis = timing.noProgress.toMillis(),
            )
        }
    }
}

@Serializable
enum class RuntimeProgressStage {
    STARTING,
    GRADLE_IMPORT,
    MODEL_SETTLEMENT,
    IDE_INDEXING,
    SOURCE_INDEX,
    REFERENCE_INDEX,
    SEMANTIC_GRAPH,
}
