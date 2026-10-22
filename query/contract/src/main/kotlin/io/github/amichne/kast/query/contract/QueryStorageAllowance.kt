package io.github.amichne.kast.query.contract

sealed interface QueryStorageAllowance {
    data class Bounded(val limit: QueryByteLimit) : QueryStorageAllowance

    data object Unlimited : QueryStorageAllowance

    fun permits(bytes: Long): Boolean =
        when (this) {
            is Bounded -> bytes <= limit.value
            Unlimited -> true
        }
}
