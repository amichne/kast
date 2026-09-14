package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.HexFormat

internal fun ByteArray.sha256(): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))

internal fun String.isCanonicalSha256(): Boolean =
    length == RELATION_CONTINUATION_FINGERPRINT_LENGTH &&
        all { character -> character in '0'..'9' || character in 'a'..'f' }

internal fun <Value, Failure> Refinement<Value, Failure>.refinedInvariant(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Internally derived relation value violated its invariant")
    }
