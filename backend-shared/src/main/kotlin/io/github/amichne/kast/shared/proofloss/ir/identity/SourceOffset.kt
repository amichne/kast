package io.github.amichne.kast.shared.proofloss.ir

@JvmInline
value class SourceOffset private constructor(val value: Int) : Comparable<SourceOffset> {
    override fun compareTo(other: SourceOffset): Int = value.compareTo(other.value)

    companion object {
        fun valid(value: Int): SourceOffset =
            if (value >= 0) SourceOffset(value) else throw IllegalArgumentException("Source offset must not be negative")
    }
}
