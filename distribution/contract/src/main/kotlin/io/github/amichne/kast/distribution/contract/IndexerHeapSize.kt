package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.kernel.Refinement

/** Maximum sidecar heap in MiB. Launch policy is independent of semantic/cache identity. */
@JvmInline
value class IndexerHeapSize private constructor(val mebibytes: Int) {
    companion object {
        const val SETTING = "KAST_INDEXER_MAX_HEAP"
        val Default = IndexerHeapSize(1536)
        private const val INITIAL_MEBIBYTES = 256
        val minimumMebibytes: Int
            get() = INITIAL_MEBIBYTES

        /** Process environment has already taken precedence over saved configuration at the launcher boundary. */
        fun parse(raw: String?): Refinement<IndexerHeapSize, IndexerHeapFailure> {
            if (raw == null) return Refinement.Refined(Default)
            if (!Regex("[0-9]+[mg]").matches(raw)) return Refinement.Rejected(IndexerHeapFailure.INVALID_SYNTAX)
            val units = raw.dropLast(1).toLongOrNull() ?: return Refinement.Rejected(IndexerHeapFailure.OVERFLOW)
            val scale = if (raw.last() == 'g') 1024 else 1
            if (units > Int.MAX_VALUE / scale) return Refinement.Rejected(IndexerHeapFailure.OVERFLOW)
            val mib = (units * scale).toInt()
            if (mib < INITIAL_MEBIBYTES) return Refinement.Rejected(IndexerHeapFailure.BELOW_INITIAL_HEAP)
            return Refinement.Refined(IndexerHeapSize(mib))
        }
    }
}

enum class IndexerHeapFailure {
    INVALID_SYNTAX,
    OVERFLOW,
    BELOW_INITIAL_HEAP,
}
