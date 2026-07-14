package io.github.amichne.kast.api.continuation

@JvmInline
value class ContinuationCapacity private constructor(
    internal val value: Int,
) {
    companion object {
        fun of(value: Int): ContinuationCapacity {
            require(value > 0) { "Continuation capacity must be positive" }
            return ContinuationCapacity(value)
        }
    }
}
