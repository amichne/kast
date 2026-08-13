package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement

/** Marker for a projection that contains no live IntelliJ project, PSI, VFS, or scope object. */
interface NativeDetachedDefinition

enum class NativeProjectionByteCountFailure {
    NEGATIVE,
}

@JvmInline
value class NativeProjectionByteCount private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<NativeProjectionByteCount, NativeProjectionByteCountFailure>.
         *
         * Establishes the non-negative encoded size of detached definition projections.
         * [NativeProjectionByteCountFailure] is the closed expected failure. Raw byte counts may be
         * extracted only at projection, metrics, or transport boundaries.
         */
        fun parse(
            raw: Long,
        ): Refinement<NativeProjectionByteCount, NativeProjectionByteCountFailure> =
            if (raw >= 0L) {
                Refinement.Refined(NativeProjectionByteCount(raw))
            } else {
                Refinement.Rejected(NativeProjectionByteCountFailure.NEGATIVE)
            }
    }
}
