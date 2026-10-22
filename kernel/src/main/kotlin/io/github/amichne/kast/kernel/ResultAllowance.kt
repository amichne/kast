package io.github.amichne.kast.kernel

sealed interface ResultAllowance {
    data class Bounded(val limit: ResultLimit) : ResultAllowance

    data object Unlimited : ResultAllowance

    fun permits(count: Int): Boolean =
        when (this) {
            is Bounded -> count <= limit.value
            Unlimited -> true
        }

    fun hasCapacity(count: Int): Boolean =
        when (this) {
            is Bounded -> count < limit.value
            Unlimited -> true
        }
}
