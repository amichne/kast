package io.github.amichne.kast.api.continuation

import java.time.Duration

@JvmInline
value class ContinuationTtl private constructor(
    internal val nanoseconds: Long,
) {
    companion object {
        fun of(duration: Duration): ContinuationTtl {
            require(!duration.isZero && !duration.isNegative) {
                "Continuation time to live must be positive"
            }
            val nanoseconds = try {
                duration.toNanos()
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("Continuation time to live must fit in nanoseconds", failure)
            }
            return ContinuationTtl(nanoseconds)
        }
    }
}
