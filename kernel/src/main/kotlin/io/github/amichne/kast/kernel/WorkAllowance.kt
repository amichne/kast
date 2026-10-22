package io.github.amichne.kast.kernel

sealed interface WorkAllowance {
    data class Bounded(val limit: WorkUnitLimit) : WorkAllowance

    data object Unlimited : WorkAllowance

    fun permits(count: Long): Boolean =
        when (this) {
            is Bounded -> count <= limit.value
            Unlimited -> true
        }

    fun hasCapacity(count: Long): Boolean =
        when (this) {
            is Bounded -> count < limit.value
            Unlimited -> true
        }

    fun remainingAfter(count: Long): Refinement<WorkAllowance, PositiveLimitFailure> =
        when (this) {
            Unlimited -> Refinement.Refined(this)
            is Bounded ->
                when (val remaining = WorkUnitLimit.parse(limit.value - count)) {
                    is Refinement.Refined -> Refinement.Refined(Bounded(remaining.value))
                    is Refinement.Rejected -> remaining
                }
        }

    fun intersect(other: WorkAllowance): WorkAllowance =
        when (this) {
            Unlimited -> other
            is Bounded ->
                when (other) {
                    Unlimited -> this
                    is Bounded -> if (limit.value <= other.limit.value) this else other
                }
        }
}
