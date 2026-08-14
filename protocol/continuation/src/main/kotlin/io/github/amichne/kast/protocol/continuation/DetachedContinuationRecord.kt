package io.github.amichne.kast.protocol.continuation

import java.nio.charset.StandardCharsets

/** Immutable canonical record detached from every live host or query object. */
@ConsistentCopyVisibility
data class DetachedContinuationRecord private constructor(
    val canonicalPayload: String,
    val encodedBytes: ContinuationByteCount,
) {
    companion object {
        /**
         * Proof transition: `String -> DetachedContinuationRecord`.
         *
         * Establishes an immutable detached payload with its exact non-negative UTF-8 byte count.
         * Raw payload extraction is permitted only at operation projection and transport boundaries.
         */
        fun fromCanonical(canonical: String): DetachedContinuationRecord =
            DetachedContinuationRecord(
                canonicalPayload = canonical,
                encodedBytes = ContinuationByteCount(
                    canonical.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                ),
            )
    }
}
