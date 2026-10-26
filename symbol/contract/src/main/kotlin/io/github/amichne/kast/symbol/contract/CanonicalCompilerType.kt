package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement

/** Canonical K2 type rendering with insignificant whitespace removed. */
@JvmInline
value class CanonicalCompilerType internal constructor(val value: String) {
    companion object {
        /** The compiler boundary supplies the type; parsing alone establishes only its canonical encoding. */
        fun fromCompiler(raw: String): Refinement<CanonicalCompilerType, CanonicalCompilerTypeFailure> =
            when (val type = canonicalType(raw)) {
                null -> Refinement.Rejected(CanonicalCompilerTypeFailure.INVALID_ENCODING)
                else -> Refinement.Refined(type)
            }
    }
}

enum class CanonicalCompilerTypeFailure {
    INVALID_ENCODING
}
