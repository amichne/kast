package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SourceReadAnchorDocumentFailure {
    UNKNOWN_TOKEN_FAMILY,
    INVALID_TOKEN_STRUCTURE,
    INVALID_PAYLOAD_ENCODING,
    PAYLOAD_DIGEST_MISMATCH,
}

@Serializable
sealed interface SourceReadAnchorDocument {
    @Serializable @SerialName("candidate") data class Candidate(val selector: ProtocolText) : SourceReadAnchorDocument

    @Serializable @SerialName("symbol") data class Symbol(val selector: ProtocolText) : SourceReadAnchorDocument

    @Serializable @SerialName("source") data class Source(val selector: ProtocolText) : SourceReadAnchorDocument

    companion object {
        /** Refines one opaque selector to its sole disjoint anchor family. */
        fun admit(selector: ProtocolText): Refinement<SourceReadAnchorDocument, SourceReadAnchorDocumentFailure> {
            if (selector.value.startsWith("exact:v4:") || selector.value.startsWith("candidate:v4:")) {
                return when (val handle = HostedSymbolHandle.parse(selector)) {
                    is Refinement.Refined ->
                        Refinement.Refined(
                            when (handle.value.family) {
                                HostedSymbolHandleFamily.EXACT -> Symbol(selector)
                                HostedSymbolHandleFamily.CANDIDATE -> Candidate(selector)
                            }
                        )
                    is Refinement.Rejected ->
                        Refinement.Rejected(SourceReadAnchorDocumentFailure.INVALID_TOKEN_STRUCTURE)
                }
            }
            return admitInline(selector)
        }

        private fun admitInline(
            selector: ProtocolText
        ): Refinement<SourceReadAnchorDocument, SourceReadAnchorDocumentFailure> {
            val parts = selector.value.split(':')
            val family =
                when (val admitted = admitSourceReadAnchorFamily(parts)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val payloadIndex = if (family == SourceReadAnchorFamily.SOURCE) 1 else 2
            val digestIndex = payloadIndex + 1
            val payload =
                try {
                    Base64.getUrlDecoder().decode(parts[payloadIndex])
                } catch (_: IllegalArgumentException) {
                    return Refinement.Rejected(SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING)
                }
            if (
                payload.isEmpty() ||
                    Base64.getUrlEncoder().withoutPadding().encodeToString(payload) != parts[payloadIndex]
            ) {
                return Refinement.Rejected(SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING)
            }
            try {
                payload.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return Refinement.Rejected(SourceReadAnchorDocumentFailure.INVALID_PAYLOAD_ENCODING)
            }
            if (parts[digestIndex] != sourceReadSha256(payload)) {
                return Refinement.Rejected(SourceReadAnchorDocumentFailure.PAYLOAD_DIGEST_MISMATCH)
            }
            return Refinement.Refined(
                when (family) {
                    SourceReadAnchorFamily.CANDIDATE -> Candidate(selector)
                    SourceReadAnchorFamily.SYMBOL -> Symbol(selector)
                    SourceReadAnchorFamily.SOURCE -> Source(selector)
                }
            )
        }
    }
}

private enum class SourceReadAnchorFamily {
    CANDIDATE,
    SYMBOL,
    SOURCE,
}

private fun sourceReadSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
    }

private fun admitSourceReadAnchorFamily(
    parts: List<String>
): Refinement<SourceReadAnchorFamily, SourceReadAnchorDocumentFailure> =
    when {
        parts.size == INLINE_SYMBOL_COMPONENTS && parts[0] == "candidate" && parts[1] in setOf("v2", "v3") ->
            Refinement.Refined(SourceReadAnchorFamily.CANDIDATE)
        parts.size == INLINE_SYMBOL_COMPONENTS && parts[0] == "exact" && parts[1] in setOf("v2", "v3") ->
            Refinement.Refined(SourceReadAnchorFamily.SYMBOL)
        parts.size == INLINE_SOURCE_COMPONENTS && parts[0] in setOf("source-selector-v1", "source-selector-v2") ->
            Refinement.Refined(SourceReadAnchorFamily.SOURCE)
        parts.firstOrNull() in setOf("candidate", "exact", "source-selector-v1", "source-selector-v2") ->
            Refinement.Rejected(SourceReadAnchorDocumentFailure.INVALID_TOKEN_STRUCTURE)
        else -> Refinement.Rejected(SourceReadAnchorDocumentFailure.UNKNOWN_TOKEN_FAMILY)
    }

private const val INLINE_SYMBOL_COMPONENTS = 4
private const val INLINE_SOURCE_COMPONENTS = 3

private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
