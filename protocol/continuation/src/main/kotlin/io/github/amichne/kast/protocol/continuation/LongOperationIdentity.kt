package io.github.amichne.kast.protocol.continuation

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

enum class LongOperationIdFailure {
    MALFORMED,
    NON_CANONICAL,
}

@JvmInline
value class LongOperationId private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<LongOperationId, LongOperationIdFailure>`.
         *
         * Establishes a canonical lowercase UUID identity that can be copied across poll requests.
         * [LongOperationIdFailure] is the closed expected failure. Raw extraction is permitted only
         * at transport serialization and this parsing boundary.
         */
        fun parse(raw: String): Refinement<LongOperationId, LongOperationIdFailure> {
            val parsed = try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                return Refinement.Rejected(LongOperationIdFailure.MALFORMED)
            }
            return if (parsed.toString() == raw) {
                Refinement.Refined(LongOperationId(raw))
            } else {
                Refinement.Rejected(LongOperationIdFailure.NON_CANONICAL)
            }
        }

        /** Proof transition: secure random UUID generation to one canonical [LongOperationId]. */
        fun random(): LongOperationId = LongOperationId(UUID.randomUUID().toString())
    }
}

fun interface LongOperationIdIssuer {
    /** Issues one refined identity; collision admission remains store-owned. */
    fun issue(): LongOperationId

    companion object {
        val Random: LongOperationIdIssuer = LongOperationIdIssuer(LongOperationId::random)
    }
}

@JvmInline
value class LongOperationRequester private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> LongOperationRequester`.
         *
         * Establishes an opaque SHA-256 identity for the canonical requester. Raw requester material
         * may be extracted only at request admission.
         */
        fun fromCanonical(canonical: String): LongOperationRequester =
            LongOperationRequester(longOperationDigest("requester", canonical))
    }
}

@JvmInline
value class LongOperationRuntimeEpoch private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> LongOperationRuntimeEpoch`.
         *
         * Establishes an opaque SHA-256 identity for the admitted runtime epoch. Raw epoch material
         * may be extracted only by runtime composition.
         */
        fun fromCanonical(canonical: String): LongOperationRuntimeEpoch =
            LongOperationRuntimeEpoch(longOperationDigest("runtime-epoch", canonical))
    }
}

@JvmInline
value class LongOperationCapability private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> LongOperationCapability`.
         *
         * Establishes an opaque SHA-256 identity for the declared operation capability. Raw
         * capability material may be extracted only at operation routing.
         */
        fun fromCanonical(canonical: String): LongOperationCapability =
            LongOperationCapability(longOperationDigest("capability", canonical))
    }
}

@JvmInline
value class LongOperationInputIdentity private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> LongOperationInputIdentity`.
         *
         * Establishes an opaque SHA-256 identity for the complete canonical operation input. Raw
         * input material may be extracted only at request normalization.
         */
        fun fromCanonical(canonical: String): LongOperationInputIdentity =
            LongOperationInputIdentity(longOperationDigest("input", canonical))
    }
}

/** Exact detached authority every start, poll, completion, and cancellation must reproduce. */
data class LongOperationBinding(
    val workspaceRoot: CanonicalWorkspaceRoot,
    val requester: LongOperationRequester,
    val runtimeEpoch: LongOperationRuntimeEpoch,
    val declaredCapability: LongOperationCapability,
    val inputIdentity: LongOperationInputIdentity,
)

private fun longOperationDigest(
    domain: String,
    canonical: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(domain.toByteArray(StandardCharsets.UTF_8))
    digest.update(0.toByte())
    digest.update(canonical.toByteArray(StandardCharsets.UTF_8))
    return HexFormat.of().formatHex(digest.digest())
}
