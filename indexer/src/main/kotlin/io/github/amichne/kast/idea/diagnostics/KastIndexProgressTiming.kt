package io.github.amichne.kast.idea.diagnostics

import io.github.amichne.kast.api.contract.RuntimeProgressTiming
import java.time.Instant

internal sealed interface KastIndexProgressTiming {
    data object Unobserved : KastIndexProgressTiming

    @ConsistentCopyVisibility
    data class Observed private constructor(
        val stageStartedAt: Instant,
        val lastProgressAt: Instant,
    ) : KastIndexProgressTiming {
        /**
         * Proof transition:
         * `(KastIndexProgressTiming.Observed, Instant) -> KastIndexProgressTiming.Observed`.
         *
         * Derives the next progress-clock state while preserving the stage
         * start and the invariant that progress time never moves backward. The
         * raw wall-clock observation enters only at the diagnostics boundary.
         */
        fun progressedAt(observedAt: Instant): Observed = Observed(
            stageStartedAt = stageStartedAt,
            lastProgressAt = if (observedAt.isBefore(lastProgressAt)) lastProgressAt else observedAt,
        )

        /**
         * Proof transition: `(KastIndexProgressTiming.Observed, Instant) -> RuntimeProgressTiming`.
         *
         * Derives bounded elapsed and no-progress durations from retained stage
         * timestamps. Raw epoch milliseconds are never exposed inside the
         * diagnostics model.
         */
        fun runtimeTimingAt(observedAt: Instant): RuntimeProgressTiming = RuntimeProgressTiming.between(
            stageStartedAt = stageStartedAt,
            lastProgressAt = lastProgressAt,
            observedAt = observedAt,
        )

        companion object {
            internal fun startedAt(observedAt: Instant): Observed = Observed(observedAt, observedAt)
        }
    }

    companion object {
        /**
         * Proof transition: `Instant -> KastIndexProgressTiming.Observed`.
         *
         * Establishes one initialized progress clock whose last-progress time
         * cannot precede its stage start. The wall clock is read only by the
         * diagnostics effect boundary before this transition.
         */
        fun startedAt(observedAt: Instant): Observed = Observed.startedAt(observedAt)
    }
}
