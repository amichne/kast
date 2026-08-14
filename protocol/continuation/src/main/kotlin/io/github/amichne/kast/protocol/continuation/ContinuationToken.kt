package io.github.amichne.kast.protocol.continuation

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID

enum class ContinuationTokenFailure {
    MALFORMED,
    NON_CANONICAL,
}

@JvmInline
value class ContinuationToken private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<ContinuationToken, ContinuationTokenFailure>`.
         *
         * Establishes a canonical lowercase UUID token that is safe to copy verbatim.
         * [ContinuationTokenFailure] is the closed expected failure. Raw token extraction is
         * permitted only at transport serialization and this parsing boundary.
         */
        fun parse(raw: String): Refinement<ContinuationToken, ContinuationTokenFailure> {
            val parsed = try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                return Refinement.Rejected(ContinuationTokenFailure.MALFORMED)
            }
            return if (parsed.toString() == raw) {
                Refinement.Refined(ContinuationToken(raw))
            } else {
                Refinement.Rejected(ContinuationTokenFailure.NON_CANONICAL)
            }
        }

        /** Proof transition: secure random UUID generation to one canonical [ContinuationToken]. */
        fun random(): ContinuationToken = ContinuationToken(UUID.randomUUID().toString())
    }
}

fun interface ContinuationTokenIssuer {
    /** Issues one already-refined opaque token; collision admission remains store-owned. */
    fun issue(): ContinuationToken

    companion object {
        val Random: ContinuationTokenIssuer = ContinuationTokenIssuer(ContinuationToken::random)
    }
}
