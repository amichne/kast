package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

enum class SourceLineRangeDocumentFailure { INVALID_LINE, REVERSED }

/** Inclusive one-based source lines, distinct from zero-based UTF-16 offsets. */
@ConsistentCopyVisibility
data class SourceLineRangeDocument private constructor(
    val startInclusive: SourceLineNumberDocument,
    val endInclusive: SourceLineNumberDocument,
) {
    companion object {
        /** Refines wire line numbers into a positive ordered range; extraction belongs to codecs. */
        fun parse(
            startInclusive: Long,
            endInclusive: Long,
        ): Refinement<SourceLineRangeDocument, SourceLineRangeDocumentFailure> {
            val start = when (val admitted = SourceLineNumberDocument.parse(startInclusive)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(admitted.failure)
            }
            val end = when (val admitted = SourceLineNumberDocument.parse(endInclusive)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(admitted.failure)
            }
            if (endInclusive < startInclusive) {
                return Refinement.Rejected(SourceLineRangeDocumentFailure.REVERSED)
            }
            return Refinement.Refined(SourceLineRangeDocument(
                start,
                end,
            ))
        }
    }
}

@JvmInline
value class SourceLineNumberDocument private constructor(val value: Long) {
    companion object {
        /** Refines a wire line coordinate into the positive, UTF-16-document-bounded domain. */
        fun parse(raw: Long): Refinement<SourceLineNumberDocument, SourceLineRangeDocumentFailure> =
            if (raw in 1..Int.MAX_VALUE.toLong() + 1) Refinement.Refined(SourceLineNumberDocument(raw))
            else Refinement.Rejected(SourceLineRangeDocumentFailure.INVALID_LINE)
    }
}
