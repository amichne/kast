package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement

private const val TRAVERSAL_CONTINUATION_FINGERPRINT_LENGTH = 64

enum class TraversalContinuationFingerprintFailure {
    INVALID_SHA256
}

@JvmInline
value class TraversalContinuationFingerprint private constructor(val value: String) {
    init {
        require(
            value.length == TRAVERSAL_CONTINUATION_FINGERPRINT_LENGTH &&
                value.all { character -> character in '0'..'9' || character in 'a'..'f' }
        )
    }

    companion object {
        fun parse(raw: String): Refinement<TraversalContinuationFingerprint, TraversalContinuationFingerprintFailure> =
            if (
                raw.length == TRAVERSAL_CONTINUATION_FINGERPRINT_LENGTH &&
                    raw.all { character -> character in '0'..'9' || character in 'a'..'f' }
            ) {
                Refinement.Refined(TraversalContinuationFingerprint(raw))
            } else {
                Refinement.Rejected(TraversalContinuationFingerprintFailure.INVALID_SHA256)
            }

        internal fun established(raw: String): TraversalContinuationFingerprint = TraversalContinuationFingerprint(raw)
    }
}
