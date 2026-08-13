package io.github.amichne.kast.kernel

/**
 * Closed result of refining a boundary value into a stronger domain representation.
 *
 * Callers must consume [Refined.value] and may handle every expected failure through
 * [Rejected.failure] without exceptions, sentinels, nullable values, or Boolean protocols.
 */
sealed interface Refinement<out Strong, out Failure> {
    data class Refined<Strong>(
        val value: Strong,
    ) : Refinement<Strong, Nothing>

    data class Rejected<Failure>(
        val failure: Failure,
    ) : Refinement<Nothing, Failure>
}
