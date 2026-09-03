package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest

private const val SOURCE_TEXT_IDENTITY_PREFIX = "intellij-document-utf16be-sha256-v1|"
private const val SHA256_HEX_LENGTH = 64
private const val HEX_RADIX = 16

enum class SourceTextIdentityFailure {
    INVALID_FORMAT,
}

/** Exact digest identity of one normalized committed IntelliJ document. */
@JvmInline
value class SourceTextIdentity private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<SourceTextIdentity,
         * SourceTextIdentityFailure>`.
         *
         * Establishes the versioned lowercase SHA-256 identity format used for committed
         * IntelliJ document text. [SourceTextIdentityFailure] is the closed expected failure.
         * Raw identity text may enter only at selector-token restoration boundaries.
         */
        fun parse(
            raw: String,
        ): Refinement<SourceTextIdentity, SourceTextIdentityFailure> {
            val digest = raw.removePrefix(SOURCE_TEXT_IDENTITY_PREFIX)
            return if (
                raw.length == SOURCE_TEXT_IDENTITY_PREFIX.length + SHA256_HEX_LENGTH &&
                digest.length == SHA256_HEX_LENGTH &&
                digest.all { character ->
                    character in '0'..'9' || character in 'a'..'f'
                }
            ) {
                Refinement.Refined(SourceTextIdentity(raw))
            } else {
                Refinement.Rejected(SourceTextIdentityFailure.INVALID_FORMAT)
            }
        }

        /**
         * Proof transition: normalized committed `CharSequence -> SourceTextIdentity`.
         *
         * Hashes the exact Java [Char] sequence as big-endian UTF-16 code units without a byte
         * order mark. The IntelliJ adapter is the only boundary permitted to supply document
         * text and must first prove that the document is committed and line-ending-normalized.
         */
        fun fromNormalizedCommittedText(
            text: CharSequence,
        ): SourceTextIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            for (index in 0 until text.length) {
                val codeUnit = text[index].code
                digest.update((codeUnit ushr Byte.SIZE_BITS).toByte())
                digest.update(codeUnit.toByte())
            }
            val hexadecimal = digest.digest().joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(HEX_RADIX).padStart(2, '0')
            }
            return SourceTextIdentity(SOURCE_TEXT_IDENTITY_PREFIX + hexadecimal)
        }
    }
}
