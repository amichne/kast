package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement

/** Inclusive one-based lines proven against the complete normalized snapshot text. */
class SourceLineRange private constructor(
    val startInclusive: SourceLineNumber,
    val endInclusive: SourceLineNumber,
) {
    companion object {
        internal fun fromCommittedText(text: String, range: SourceRange): SourceLineRange {
            val startOffset = range.startInclusive.value
            val lastOffset = (range.endExclusive.value - 1).coerceAtLeast(startOffset)
            var precedingLines = 0L
            for (index in 0 until startOffset) if (text[index] == '\n') precedingLines += 1
            val start = lineNumber(precedingLines + 1)
            for (index in startOffset until lastOffset) if (text[index] == '\n') precedingLines += 1
            return SourceLineRange(start, lineNumber(precedingLines + 1))
        }
    }
}

@JvmInline
value class SourceLineNumber private constructor(val value: Long) {
    companion object {
        /** Refines a one-based line coordinate; raw extraction belongs to the protocol adapter. */
        fun parse(raw: Long): Refinement<SourceLineNumber, SourceLineNumberFailure> =
            if (raw in 1..Int.MAX_VALUE.toLong() + 1) Refinement.Refined(SourceLineNumber(raw))
            else Refinement.Rejected(SourceLineNumberFailure.OUT_OF_RANGE)
    }
}

enum class SourceLineNumberFailure { OUT_OF_RANGE }

private fun lineNumber(value: Long): SourceLineNumber = when (val admitted = SourceLineNumber.parse(value)) {
    is Refinement.Refined -> admitted.value
    is Refinement.Rejected -> error("A proven document range produced an invalid line coordinate")
}
