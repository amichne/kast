package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement

/** Dotted package identity admitted independently of directory path spelling. */
@JvmInline
internal value class PublicQueryPackageName private constructor(val value: String) {
    companion object {
        private val grammar = Regex("(?!.*\\.(?:[0-9.]|$))[A-Za-z_][A-Za-z0-9_.]*")

        /**
         * Refines String to a dotted package name or [PublicQueryPackageNameFailure]. Raw extraction belongs only to
         * query serialization and canonical protocol lowering.
         */
        fun parse(raw: String): Refinement<PublicQueryPackageName, PublicQueryPackageNameFailure> =
            when {
                raw.length > 1_048_576 -> Refinement.Rejected(PublicQueryPackageNameFailure.TOO_LONG)
                !grammar.matches(raw) -> Refinement.Rejected(PublicQueryPackageNameFailure.INVALID_NAME)
                else -> Refinement.Refined(PublicQueryPackageName(raw))
            }
    }
}

internal enum class PublicQueryPackageNameFailure {
    TOO_LONG,
    INVALID_NAME,
}
