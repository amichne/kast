package io.github.amichne.kast.change.verify

import io.github.amichne.kast.kernel.Refinement

/** Bounded opaque exact reference, issued by the query codec only after live verification. */
@JvmInline
value class BodyExactReference private constructor(val value: String) {
    companion object {
        private const val MIN_TOKEN_LENGTH = 10
        private const val MAX_TOKEN_LENGTH = 1_048_576

        fun parse(raw: String): Refinement<BodyExactReference, LiveReceiptFailure> =
            if (
                raw.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH &&
                    raw.startsWith("exact:v") &&
                    raw.none(Char::isISOControl)
            )
                Refinement.Refined(BodyExactReference(raw))
            else Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
    }
}
