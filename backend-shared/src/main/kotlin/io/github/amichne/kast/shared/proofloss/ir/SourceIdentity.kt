package io.github.amichne.kast.shared.proofloss.ir

import io.github.amichne.kast.api.contract.NormalizedPath

@JvmInline
value class SourceOffset private constructor(val value: Int) : Comparable<SourceOffset> {
    override fun compareTo(other: SourceOffset): Int = value.compareTo(other.value)

    companion object {
        fun valid(value: Int): SourceOffset =
            if (value >= 0) SourceOffset(value) else throw IllegalArgumentException("Source offset must not be negative")
    }
}

data class SourceSpan(
    val filePath: NormalizedPath,
    val startOffset: SourceOffset,
    val endOffset: SourceOffset,
) {
    init {
        require(endOffset >= startOffset)
    }
}

data class FunctionId(
    val filePath: NormalizedPath,
    val declarationOffset: SourceOffset,
) : Comparable<FunctionId> {
    override fun compareTo(other: FunctionId): Int =
        compareValuesBy(this, other, { it.filePath }, { it.declarationOffset })
}

data class TrackedValueId(
    val filePath: NormalizedPath,
    val declarationOffset: SourceOffset,
)
