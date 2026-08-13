package io.github.amichne.kast.protocol.continuation

import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

@JvmInline
value class ContinuationRequestFingerprint private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> ContinuationRequestFingerprint`.
         *
         * Establishes an opaque SHA-256 identity for the complete canonical normalized request.
         * Raw request material may be extracted only at the operation request-normalization boundary.
         */
        fun fromCanonical(canonical: String): ContinuationRequestFingerprint =
            ContinuationRequestFingerprint(canonicalDigest("request", canonical))
    }
}

@JvmInline
value class ContinuationScopeFingerprint private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> ContinuationScopeFingerprint`.
         *
         * Establishes an opaque SHA-256 identity for the complete canonical detached scope.
         * Raw scope material may be extracted only where an operation compiles its scope.
         */
        fun fromCanonical(canonical: String): ContinuationScopeFingerprint =
            ContinuationScopeFingerprint(canonicalDigest("scope", canonical))
    }
}

@JvmInline
value class ContinuationOrderFingerprint private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> ContinuationOrderFingerprint`.
         *
         * Establishes an opaque SHA-256 identity for the operation's canonical total ordering.
         * Raw ordering material may be extracted only at the operation ordering boundary.
         */
        fun fromCanonical(canonical: String): ContinuationOrderFingerprint =
            ContinuationOrderFingerprint(canonicalDigest("order", canonical))
    }
}

@JvmInline
value class ContinuationResourceOwner private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> ContinuationResourceOwner`.
         *
         * Establishes an opaque SHA-256 identity for the canonical resource owner.
         * Raw ownership material may be extracted only at the resource-admission boundary.
         */
        fun fromCanonical(canonical: String): ContinuationResourceOwner =
            ContinuationResourceOwner(canonicalDigest("owner", canonical))
    }
}

/** Complete detached identity that every token access must reproduce exactly. */
data class ContinuationBinding(
    val lease: SemanticReadLease,
    val normalizedRequest: ContinuationRequestFingerprint,
    val scope: ContinuationScopeFingerprint,
    val order: ContinuationOrderFingerprint,
    val owner: ContinuationResourceOwner,
)

private fun canonicalDigest(
    domain: String,
    canonical: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(domain.toByteArray(StandardCharsets.UTF_8))
    digest.update(0.toByte())
    digest.update(canonical.toByteArray(StandardCharsets.UTF_8))
    return HexFormat.of().formatHex(digest.digest())
}
