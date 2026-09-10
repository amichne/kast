package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.*
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

enum class CanonicalSelectorEncodingFailure {
    UNSUPPORTED_SCOPE,
    TOKEN_REJECTED,
}

sealed interface CanonicalSelectorEncoding {
    data class Encoded(val token: ProtocolText) : CanonicalSelectorEncoding
    data class Rejected(
        val failure: CanonicalSelectorEncodingFailure,
    ) : CanonicalSelectorEncoding
}

enum class CanonicalSelectorDecodingFailure {
    INVALID_TOKEN_STRUCTURE,
    INVALID_PAYLOAD_ENCODING,
    PAYLOAD_DIGEST_MISMATCH,
    MALFORMED_DOCUMENT,
    INVALID_DOCUMENT,
    INCOMPATIBLE_WORKSPACE,
    INCOMPATIBLE_AUTHORITY,
    STALE_AUTHORITY,
    UNSUPPORTED_REFERENCE_VERSION,
    LIVE_AUTHORITY_REQUIRED,
}

sealed interface CanonicalSelectorDecoding<out Value> {
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

object CanonicalSelectorCodec {
    /**
     * Proof transition: `CandidateSelector -> CanonicalSelectorEncoding`.
     *
     * [CanonicalSelectorEncoding.Encoded] establishes one digest-bound, discriminated candidate
     * document. [CanonicalSelectorEncodingFailure] closes unsupported declaration scopes and
     * public-text admission failure. Raw document text leaves only at the protocol-token edge.
     */
    fun encodeCandidate(selector: CandidateSelector): CanonicalSelectorEncoding {
        val retainedScopeNeeded = when (selector) {
            is CandidateSelector.Declaration -> false
            is CandidateSelector.File -> !selector.hasHistoricalFileScope(selector.file)
            is CandidateSelector.Range -> !selector.hasHistoricalFileScope(selector.file)
        }
        val retainedScope = if (retainedScopeNeeded) {
            when (val projected = selector.scope.selectorDocumentProjection()) {
                is SelectorScopeDocumentProjection.Projected -> SelectorReadScopeDocument(
                    selector.scope.sourceKinds.name, selector.scope.generatedSources.name,
                    projected.kind, projected.file, projected.libraries, selector.constraints.selectorDocument())
                SelectorScopeDocumentProjection.Rejected -> return CanonicalSelectorEncoding.Rejected(
                    CanonicalSelectorEncodingFailure.UNSUPPORTED_SCOPE)
            }
        } else null
        val document = when (selector) {
            is CandidateSelector.Declaration -> {
                val selection = selector.selection
                val location = selection.candidate.location as
                    SymbolDiscoveryCandidateLocation.Declaration
                val scope = when (val projection = selection.scope.selectorDocumentProjection()) {
                    is SelectorScopeDocumentProjection.Projected -> projection
                    SelectorScopeDocumentProjection.Rejected ->
                        return CanonicalSelectorEncoding.Rejected(
                            CanonicalSelectorEncodingFailure.UNSUPPORTED_SCOPE,
                        )
                }
                val file = selection.candidate.location.file.selectorDocumentProjection()
                CandidateSelectorDocument.Declaration(
                    root = selection.lease.workspaceRoot.value,
                    generation = (selection.lease as? SemanticReadLease)?.generation?.value,
                    live = (selection.lease as? LiveSemanticReadAuthority)?.reference?.selectorDocument(),
                    sourceKinds = selection.scope.sourceKinds.name,
                    generatedSources = selection.scope.generatedSources.name,
                    scope = scope.kind,
                    scopeFile = scope.file,
                    libraries = scope.libraries,
                    constraints = selection.constraints.selectorDocument(),
                    kind = selection.candidate.kind.name,
                    name = selection.candidate.name.value,
                    fileType = file.kind,
                    file = file.value,
                    offset = location.offset.value,
                )
            }
            is CandidateSelector.File -> CandidateSelectorDocument.File(
                root = selector.lease.workspaceRoot.value,
                generation = (selector.lease as? SemanticReadLease)?.generation?.value,
                live = (selector.lease as? LiveSemanticReadAuthority)?.reference?.selectorDocument(),
                file = selector.file.path.value,
                readScope = retainedScope,
            )
            is CandidateSelector.Range -> CandidateSelectorDocument.Range(
                root = selector.lease.workspaceRoot.value,
                generation = (selector.lease as? SemanticReadLease)?.generation?.value,
                live = (selector.lease as? LiveSemanticReadAuthority)?.reference?.selectorDocument(),
                file = selector.file.path.value,
                startInclusive = selector.startInclusive.value,
                endExclusive = selector.endExclusive.value,
                readScope = retainedScope,
            )
        }
        return encodeToken(
            CANDIDATE_PREFIX,
            if (selector.lease is LiveSemanticReadAuthority ||
                selector.constraints != SymbolDiscoveryConstraints.None || retainedScope != null
            ) LIVE_TOKEN_VERSION else CANDIDATE_TOKEN_VERSION,
            selectorJson.encodeToString(CandidateSelectorDocument.serializer(), document),
        )
    }

    /**
     * Proof transition: `ProtocolText -> CanonicalSelectorDecoding<CandidateSelector>`.
     *
     * A decoded value establishes token structure, digest, generated-schema decoding, and all
     * lease, scope, file, candidate, and selection invariants. [CanonicalSelectorDecodingFailure]
     * is the closed expected failure. Raw token text is extracted only by this decoder boundary.
     */
    fun decodeCandidate(
        token: ProtocolText,
        current: SemanticReadAuthority? = null,
    ): CanonicalSelectorDecoding<CandidateSelector> {
        val payload = when (
            val admission = parseToken(token, CANDIDATE_PREFIX, tokenVersion(token, CANDIDATE_TOKEN_VERSION))
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
        if (token.value.split(':')[1] == CANDIDATE_TOKEN_VERSION &&
            (document.live != null || when (document) {
                is CandidateSelectorDocument.Declaration -> document.constraints != null
                is CandidateSelectorDocument.File -> document.readScope != null
                is CandidateSelectorDocument.Range -> document.readScope != null
            })) {
            return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNSUPPORTED_REFERENCE_VERSION)
        }
        when (val identity = admitSelectorReference(document.root, document.generation, document.live, current)) {
            is CanonicalSelectorDecoding.Rejected -> return identity
            is CanonicalSelectorDecoding.Decoded -> return document.admitCandidateSelector(identity.value).asDecoding()
        }
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
            generation = (selector.lease as? SemanticReadLease)?.generation?.value,
                live = (selector.lease as? LiveSemanticReadAuthority)?.reference?.selectorDocument(),
            sourceKinds = selector.scope.sourceKinds.name,
            generatedSources = selector.scope.generatedSources.name,
            scope = scope.kind,
            scopeFile = scope.file,
            libraries = scope.libraries,
            constraints = selector.constraints.selectorDocument(),
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
            if (selector.lease is LiveSemanticReadAuthority || selector.constraints != SymbolDiscoveryConstraints.None)
                LIVE_TOKEN_VERSION else EXACT_TOKEN_VERSION,
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
    fun decodeExact(token: ProtocolText, current: SemanticReadAuthority? = null): CanonicalSelectorDecoding<SymbolSelector> {
        val payload = when (
            val admission = parseToken(token, EXACT_PREFIX, tokenVersion(token, EXACT_TOKEN_VERSION))
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
        if (token.value.split(':')[1] == EXACT_TOKEN_VERSION && (document.live != null || document.constraints != null)) {
            return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNSUPPORTED_REFERENCE_VERSION)
        }
        when (val identity = admitSelectorReference(document.root, document.generation, document.live, current)) {
            is CanonicalSelectorDecoding.Rejected -> return identity
            is CanonicalSelectorDecoding.Decoded -> return document.admitExactSelector(identity.value).asDecoding()
        }
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
private const val CANDIDATE_TOKEN_VERSION = "v2"
private const val EXACT_TOKEN_VERSION = "v2"
private const val TOKEN_PART_COUNT = 4

private const val LIVE_TOKEN_VERSION = "v3"

private fun tokenVersion(token: ProtocolText, published: String): String =
    if (token.value.split(':').getOrNull(1) == LIVE_TOKEN_VERSION) LIVE_TOKEN_VERSION
    else published

private fun LiveSemanticReadReference.selectorDocument() = LiveSelectorAuthorityDocument(
    host.value.toString(), epoch.value, contentView.name, version,
)

private fun CandidateSelector.hasHistoricalFileScope(file: SymbolDiscoveryFileIdentity.Workspace): Boolean =
    scope == SymbolSearchScope.ExactFile(file.path, SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE) && constraints == SymbolDiscoveryConstraints.None
