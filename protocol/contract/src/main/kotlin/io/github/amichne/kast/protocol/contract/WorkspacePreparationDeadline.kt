package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

/** Preparation owns this allowance; semantic execution and transport retain their own bounds. */
object WorkspacePreparationBudget {
    val limit: ElapsedTimeLimitMillis =
        when (val admitted = ElapsedTimeLimitMillis.parse(17 * 60_000L)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Invalid workspace preparation policy")
        }
}

enum class WorkspacePreparationExpiry {
    DEADLINE_EXCEEDED
}

@Serializable
enum class WorkspacePreparationDeadlineOwner {
    PREPARATION_OWNER,
    NATIVE_PREPARATION,
    REFRESH_WAITER,
}

@Serializable
data class WorkspacePreparationTiming(
    val stage: IdeLifecycleStage,
    val elapsedMillis: Long,
    val limitMillis: Long,
    val remainingMillis: Long,
    val limitingOwner: WorkspacePreparationDeadlineOwner,
)

/** One monotonic observation domain. Passing a deadline to a child never restarts its allowance. */
class WorkspacePreparationDeadline
private constructor(
    val limit: ElapsedTimeLimitMillis,
    private val startedNanos: Long,
) {
    fun remaining(nowNanos: Long): Refinement<ElapsedTimeLimitMillis, WorkspacePreparationExpiry> =
        when (val admitted = ElapsedTimeLimitMillis.parse((limit.value - elapsedMillis(nowNanos)).coerceAtLeast(0))) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(WorkspacePreparationExpiry.DEADLINE_EXCEEDED)
        }

    fun timing(
        stage: IdeLifecycleStage,
        owner: WorkspacePreparationDeadlineOwner,
        nowNanos: Long,
    ): WorkspacePreparationTiming {
        val elapsed = elapsedMillis(nowNanos)
        return WorkspacePreparationTiming(stage, elapsed, limit.value, (limit.value - elapsed).coerceAtLeast(0), owner)
    }

    private fun elapsedMillis(nowNanos: Long): Long {
        val elapsed = (nowNanos - startedNanos).coerceAtLeast(0)
        return elapsed / NANOS_PER_MILLI + if (elapsed % NANOS_PER_MILLI == 0L) 0 else 1
    }

    companion object {
        fun start(limit: ElapsedTimeLimitMillis, nowNanos: Long): WorkspacePreparationDeadline =
            WorkspacePreparationDeadline(limit, nowNanos)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
