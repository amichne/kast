package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement

enum class Utf16CodeUnitOffsetFailure {
    NEGATIVE,
}

/** One non-negative offset in normalized IntelliJ document UTF-16 code units. */
@JvmInline
value class Utf16CodeUnitOffset private constructor(
    val value: Int,
) : Comparable<Utf16CodeUnitOffset> {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<Utf16CodeUnitOffset,
         * Utf16CodeUnitOffsetFailure>`.
         */
        fun parse(
            raw: Int,
        ): Refinement<Utf16CodeUnitOffset, Utf16CodeUnitOffsetFailure> =
            if (raw < 0) {
                Refinement.Rejected(Utf16CodeUnitOffsetFailure.NEGATIVE)
            } else {
                Refinement.Refined(Utf16CodeUnitOffset(raw))
            }

    }

    override fun compareTo(other: Utf16CodeUnitOffset): Int = value.compareTo(other.value)
}

enum class Utf16CodeUnitCountFailure {
    NEGATIVE,
}

/** One non-negative normalized IntelliJ document UTF-16 code-unit count. */
@JvmInline
value class Utf16CodeUnitCount private constructor(
    val value: Int,
) {
    companion object {
        /**
         * Proof transition: `Int -> Refinement<Utf16CodeUnitCount,
         * Utf16CodeUnitCountFailure>`.
         */
        fun parse(
            raw: Int,
        ): Refinement<Utf16CodeUnitCount, Utf16CodeUnitCountFailure> =
            if (raw < 0) {
                Refinement.Rejected(Utf16CodeUnitCountFailure.NEGATIVE)
            } else {
                Refinement.Refined(Utf16CodeUnitCount(raw))
            }
    }
}
