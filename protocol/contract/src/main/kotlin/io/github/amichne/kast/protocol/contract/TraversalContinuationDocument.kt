package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable

private const val MAX_CONTINUATION_TEXT_LENGTH = 1_048_576

enum class TraversalContinuationDocumentFailure {
    UNKNOWN_TOKEN_FAMILY,
    INVALID_TOKEN_STRUCTURE,
    INVALID_PAYLOAD_ENCODING,
    PAYLOAD_DIGEST_MISMATCH,
}

@JvmInline
@Serializable(with = TraversalContinuationDocumentSerializer::class)
value class TraversalContinuationDocument private constructor(val value: String) {
    companion object {
        const val TOKEN_PATTERN: String = "^traversal-continuation:v[12]:"

        fun parse(raw: String): Refinement<TraversalContinuationDocument, TraversalContinuationDocumentFailure> {
            val parts = raw.split(':')
            if (parts.firstOrNull() != TRAVERSAL_CONTINUATION_TOKEN_FAMILY) {
                return Refinement.Rejected(TraversalContinuationDocumentFailure.UNKNOWN_TOKEN_FAMILY)
            }
            if (
                parts.size != TRAVERSAL_CONTINUATION_TOKEN_PART_COUNT ||
                    parts[1] !in setOf(TRAVERSAL_CONTINUATION_TOKEN_VERSION, "v2")
            ) {
                return Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE)
            }
            val payload =
                try {
                    Base64.getUrlDecoder().decode(parts[2])
                } catch (_: IllegalArgumentException) {
                    return Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING)
                }
            if (payload.isEmpty() || Base64.getUrlEncoder().withoutPadding().encodeToString(payload) != parts[2]) {
                return Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING)
            }
            try {
                payload.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING)
            }
            if (parts[DIGEST_PART_INDEX] != traversalContinuationSha256(payload)) {
                return Refinement.Rejected(TraversalContinuationDocumentFailure.PAYLOAD_DIGEST_MISMATCH)
            }
            if (raw.length > MAX_CONTINUATION_TEXT_LENGTH) {
                return Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE)
            }
            return Refinement.Refined(TraversalContinuationDocument(raw))
        }
    }
}

internal object TraversalContinuationDocumentSerializer :
    RefiningStringSerializer<TraversalContinuationDocument>(
        serialName = "io.github.amichne.kast.protocol.contract.TraversalContinuationDocument",
        minimumLength = 1,
        maximumLength = MAX_CONTINUATION_TEXT_LENGTH,
        pattern = TraversalContinuationDocument.TOKEN_PATTERN,
    ) {
    override fun raw(value: TraversalContinuationDocument): String = value.value

    override fun refine(raw: String): Refinement<TraversalContinuationDocument, *> =
        TraversalContinuationDocument.parse(raw)
}

private fun traversalContinuationSha256(bytes: ByteArray): String =
    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private const val DIGEST_PART_INDEX = 3
private const val TRAVERSAL_CONTINUATION_TOKEN_FAMILY = "traversal-continuation"
private const val TRAVERSAL_CONTINUATION_TOKEN_VERSION = "v1"
private const val TRAVERSAL_CONTINUATION_TOKEN_PART_COUNT = 4
