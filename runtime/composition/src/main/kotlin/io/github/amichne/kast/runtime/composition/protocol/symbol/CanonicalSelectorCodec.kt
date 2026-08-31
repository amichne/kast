package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal enum class CanonicalSelectorEncodingFailure {
    NON_DECLARATION_CANDIDATE,
    UNSUPPORTED_SCOPE,
    TOKEN_REJECTED,
}

internal sealed interface CanonicalSelectorEncoding {
    data class Encoded(val token: ProtocolText) : CanonicalSelectorEncoding
    data class Rejected(
        val failure: CanonicalSelectorEncodingFailure,
    ) : CanonicalSelectorEncoding
}

internal enum class CanonicalSelectorDecodingFailure {
    INVALID_TOKEN_STRUCTURE,
    INVALID_PAYLOAD_ENCODING,
    PAYLOAD_DIGEST_MISMATCH,
    MALFORMED_DOCUMENT,
    INVALID_DOCUMENT,
}

internal sealed interface CanonicalSelectorDecoding<out Value> {
    data class Decoded<Value>(val value: Value) : CanonicalSelectorDecoding<Value>
    data class Rejected(
        val failure: CanonicalSelectorDecodingFailure,
    ) : CanonicalSelectorDecoding<Nothing>
}

private val selectorJson = Json {
    encodeDefaults = false
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

internal object CanonicalSelectorCodec {
    /**
     * Proof transition: `SymbolDiscoverySelection -> CanonicalSelectorEncoding`.
     *
     * [CanonicalSelectorEncoding.Encoded] establishes a digest-bound candidate document produced
     * by the generated `CandidateSelectorDocument.serializer()` factory.
     * [CanonicalSelectorEncodingFailure] closes non-declaration candidates, unsupported scopes,
     * and public-text admission failure. Raw document text leaves only at the protocol-token edge.
     */
    fun encodeCandidate(selection: SymbolDiscoverySelection): CanonicalSelectorEncoding {
        val location = when (val candidateLocation = selection.candidate.location) {
            is SymbolDiscoveryCandidateLocation.Declaration -> candidateLocation
            else -> return CanonicalSelectorEncoding.Rejected(
                CanonicalSelectorEncodingFailure.NON_DECLARATION_CANDIDATE,
            )
        }
        val scope = when (val projection = selection.scope.selectorDocumentProjection()) {
            is SelectorScopeDocumentProjection.Projected -> projection
            SelectorScopeDocumentProjection.Rejected -> return CanonicalSelectorEncoding.Rejected(
                CanonicalSelectorEncodingFailure.UNSUPPORTED_SCOPE,
            )
        }
        val file = selection.candidate.location.file.selectorDocumentProjection()
        val document = CandidateSelectorDocument(
            root = selection.lease.workspaceRoot.value,
            generation = selection.lease.generation.value,
            sourceKinds = selection.scope.sourceKinds.name,
            generatedSources = selection.scope.generatedSources.name,
            scope = scope.kind,
            scopeFile = scope.file,
            libraries = scope.libraries,
            kind = selection.candidate.kind.name,
            name = selection.candidate.name.value,
            fileType = file.kind,
            file = file.value,
            offset = location.offset.value,
        )
        return encodeToken(
            CANDIDATE_PREFIX,
            CANDIDATE_TOKEN_VERSION,
            selectorJson.encodeToString(CandidateSelectorDocument.serializer(), document),
        )
    }

    /**
     * Proof transition: `ProtocolText -> CanonicalSelectorDecoding<SymbolDiscoverySelection>`.
     *
     * A decoded value establishes token structure, digest, generated-schema decoding, and all
     * lease, scope, file, candidate, and selection invariants. [CanonicalSelectorDecodingFailure]
     * is the closed expected failure. Raw token text is extracted only by this decoder boundary.
     */
    fun decodeCandidate(
        token: ProtocolText,
    ): CanonicalSelectorDecoding<SymbolDiscoverySelection> {
        val payload = when (
            val admission = parseToken(token, CANDIDATE_PREFIX, CANDIDATE_TOKEN_VERSION)
        ) {
            is SelectorTokenPayloadAdmission.Admitted -> admission.payload
            is SelectorTokenPayloadAdmission.Rejected -> return admission.failure.rejected()
        }
        val document = try {
            selectorJson.decodeFromString(CandidateSelectorDocument.serializer(), payload)
        } catch (_: SerializationException) {
            return CanonicalSelectorDecoding.Rejected(
                CanonicalSelectorDecodingFailure.MALFORMED_DOCUMENT,
            )
        } catch (_: IllegalArgumentException) {
            return CanonicalSelectorDecoding.Rejected(
                CanonicalSelectorDecodingFailure.MALFORMED_DOCUMENT,
            )
        }
        return document.admitCandidateSelection().asDecoding()
    }

    /**
     * Proof transition: `SymbolSelector -> CanonicalSelectorEncoding`.
     *
     * An encoded token establishes a digest-bound exact-selector document produced by the
     * generated `ExactSelectorDocument.serializer()` factory. [CanonicalSelectorEncodingFailure]
     * closes unsupported scopes and public-text admission failure. Raw document text leaves only
     * at the token edge.
     */
    fun encodeExact(selector: SymbolSelector): CanonicalSelectorEncoding {
        val scope = when (val projection = selector.scope.selectorDocumentProjection()) {
            is SelectorScopeDocumentProjection.Projected -> projection
            SelectorScopeDocumentProjection.Rejected -> return CanonicalSelectorEncoding.Rejected(
                CanonicalSelectorEncodingFailure.UNSUPPORTED_SCOPE,
            )
        }
        val file = selector.file.selectorDocumentProjection()
        val document = ExactSelectorDocument(
            root = selector.lease.workspaceRoot.value,
            generation = selector.lease.generation.value,
            sourceKinds = selector.scope.sourceKinds.name,
            generatedSources = selector.scope.generatedSources.name,
            scope = scope.kind,
            scopeFile = scope.file,
            libraries = scope.libraries,
            fileType = file.kind,
            file = file.value,
            start = selector.range.startInclusive,
            end = selector.range.endExclusive,
            name = selector.name.value,
            qualifiedIdentity = when (val identity = selector.qualifiedIdentity) {
                is ExactDeclarationQualifiedIdentity.Available -> identity.value
                ExactDeclarationQualifiedIdentity.Unavailable -> null
            },
            kind = selector.kind.name,
            compilerSignature = selector.signature.canonicalEncoding().value,
            compilerIdentity = selector.compilerIdentity.value,
            fingerprint = selector.fingerprint.value,
        )
        return encodeToken(
            EXACT_PREFIX,
            EXACT_TOKEN_VERSION,
            selectorJson.encodeToString(ExactSelectorDocument.serializer(), document),
        )
    }

    /**
     * Proof transition: `ProtocolText -> CanonicalSelectorDecoding<SymbolSelector>`.
     *
     * A decoded value establishes token structure, digest, generated-schema decoding, and all
     * exact compiler-evidence and fingerprint invariants. [CanonicalSelectorDecodingFailure] is
     * the closed expected failure. Raw token text is extracted only by this decoder boundary.
     */
    fun decodeExact(token: ProtocolText): CanonicalSelectorDecoding<SymbolSelector> {
        val payload = when (
            val admission = parseToken(token, EXACT_PREFIX, EXACT_TOKEN_VERSION)
        ) {
            is SelectorTokenPayloadAdmission.Admitted -> admission.payload
            is SelectorTokenPayloadAdmission.Rejected -> return admission.failure.rejected()
        }
        val document = try {
            selectorJson.decodeFromString(ExactSelectorDocument.serializer(), payload)
        } catch (_: SerializationException) {
            return CanonicalSelectorDecoding.Rejected(
                CanonicalSelectorDecodingFailure.MALFORMED_DOCUMENT,
            )
        } catch (_: IllegalArgumentException) {
            return CanonicalSelectorDecoding.Rejected(
                CanonicalSelectorDecodingFailure.MALFORMED_DOCUMENT,
            )
        }
        return document.admitExactSelector().asDecoding()
    }
}

private sealed interface SelectorTokenPayloadAdmission {
    data class Admitted(val payload: String) : SelectorTokenPayloadAdmission
    data class Rejected(
        val failure: CanonicalSelectorDecodingFailure,
    ) : SelectorTokenPayloadAdmission
}

/** Encodes one generated JSON document as a digest-bound, bounded public protocol token. */
private fun encodeToken(
    prefix: String,
    version: String,
    document: String,
): CanonicalSelectorEncoding {
    val payload = document.toByteArray(StandardCharsets.UTF_8)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    val raw = "$prefix:$version:$encoded:${sha256(payload)}"
    return when (val admitted = ProtocolText.parse(raw)) {
        is Refinement.Refined -> CanonicalSelectorEncoding.Encoded(admitted.value)
        is Refinement.Rejected -> CanonicalSelectorEncoding.Rejected(
            CanonicalSelectorEncodingFailure.TOKEN_REJECTED,
        )
    }
}

/**
 * Proof transition: `ProtocolText + expected prefix -> SelectorTokenPayloadAdmission`.
 *
 * Admission proves the token family, version, base64url encoding, SHA-256 digest, and strict UTF-8
 * payload. [CanonicalSelectorDecodingFailure] closes each expected rejection. Raw bytes leave only
 * at strict UTF-8 decoding for the generated serializer boundary.
 */
private fun parseToken(
    document: ProtocolText,
    prefix: String,
    version: String,
): SelectorTokenPayloadAdmission {
    val parts = document.value.split(':')
    if (parts.size != TOKEN_PART_COUNT || parts[0] != prefix || parts[1] != version) {
        return SelectorTokenPayloadAdmission.Rejected(
            CanonicalSelectorDecodingFailure.INVALID_TOKEN_STRUCTURE,
        )
    }
    val payload = try {
        Base64.getUrlDecoder().decode(parts[2])
    } catch (_: IllegalArgumentException) {
        return SelectorTokenPayloadAdmission.Rejected(
            CanonicalSelectorDecodingFailure.INVALID_PAYLOAD_ENCODING,
        )
    }
    if (sha256(payload) != parts[3]) {
        return SelectorTokenPayloadAdmission.Rejected(
            CanonicalSelectorDecodingFailure.PAYLOAD_DIGEST_MISMATCH,
        )
    }
    val decoded = try {
        payload.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return SelectorTokenPayloadAdmission.Rejected(
            CanonicalSelectorDecodingFailure.INVALID_PAYLOAD_ENCODING,
        )
    }
    return SelectorTokenPayloadAdmission.Admitted(decoded)
}

private fun <Value> SelectorDocumentAdmission<Value>.asDecoding():
    CanonicalSelectorDecoding<Value> = when (this) {
    is SelectorDocumentAdmission.Admitted -> CanonicalSelectorDecoding.Decoded(value)
    SelectorDocumentAdmission.Rejected -> CanonicalSelectorDecoding.Rejected(
        CanonicalSelectorDecodingFailure.INVALID_DOCUMENT,
    )
}

private fun CanonicalSelectorDecodingFailure.rejected(): CanonicalSelectorDecoding.Rejected =
    CanonicalSelectorDecoding.Rejected(this)

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private const val CANDIDATE_PREFIX = "candidate"
private const val EXACT_PREFIX = "exact"
private const val CANDIDATE_TOKEN_VERSION = "v1"
private const val EXACT_TOKEN_VERSION = "v2"
private const val TOKEN_PART_COUNT = 4
