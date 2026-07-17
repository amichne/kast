package io.github.amichne.kast.api.contract.selector

import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DigestSelectorHandleAuthority private constructor(
    private val workspaceRoot: String,
    private val backendName: String,
    private val backendVersion: String,
    private val backendInstanceId: String,
    private val semanticGeneration: () -> Long,
    private val integrityKey: ByteArray,
) : SelectorHandleAuthority {
    constructor(
        workspaceRoot: String,
        backendName: String,
        backendVersion: String,
        backendInstanceId: String,
        semanticGeneration: () -> Long,
    ) : this(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = backendVersion,
        backendInstanceId = backendInstanceId,
        semanticGeneration = semanticGeneration,
        integrityKey = randomIntegrityKey(),
    )

    init {
        require(workspaceRoot.isNotBlank()) { "workspaceRoot must not be blank" }
        require(backendName.isNotBlank()) { "backendName must not be blank" }
        require(backendVersion.isNotBlank()) { "backendVersion must not be blank" }
        require(backendInstanceId.isNotBlank()) { "backendInstanceId must not be blank" }
        require(integrityKey.size == INTEGRITY_KEY_LENGTH) { "integrityKey must be 256 bits" }
    }

    override fun issue(
        selector: KastExactSymbolSelector,
        allowedFamilies: Set<SelectorOperationFamily>,
    ): SelectorHandleAuthority.IssueResult {
        require(selector.fqName.isNotBlank()) { "selector fqName must not be blank" }
        require(selector.declarationFile.isNotBlank()) { "selector declarationFile must not be blank" }
        require(selector.declarationStartOffset >= 0) { "selector offset must not be negative" }
        requireNotNull(selector.kind) { "backend-issued selectors must carry a declaration kind" }
        val generation = semanticGeneration()
        require(generation >= 0) { "semantic generation must not be negative" }
        val claims = Claims(
            workspaceRoot = workspaceRoot,
            backendName = backendName,
            backendVersion = backendVersion,
            backendInstanceId = backendInstanceId,
            semanticGeneration = generation,
            selectorFqName = selector.fqName,
            selectorDeclarationFile = selector.declarationFile,
            selectorDeclarationStartOffset = selector.declarationStartOffset,
            selectorKind = requireNotNull(selector.kind),
            selectorContainingType = selector.containingType,
            familyMask = allowedFamilies.fold(0) { mask, family -> mask or family.wireBit },
        )
        val payload = encodeClaims(claims)
        val envelope = payload + authenticationTag(payload)
        val value = SelectorHandle.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)
        return SelectorHandleAuthority.IssueResult.Issued(SelectorHandle.parse(value))
    }

    override fun resolve(
        handle: String,
        workspaceRoot: String,
        family: SelectorOperationFamily,
    ): SelectorHandleAuthority.Resolution {
        val envelope = decodeEnvelope(handle)
            ?: return rejected(SelectorHandleAuthority.Resolution.RejectionReason.TAMPERED)
        val claims = envelope.claims
        if (claims.workspaceRoot != workspaceRoot) {
            return rejected(SelectorHandleAuthority.Resolution.RejectionReason.WRONG_WORKSPACE)
        }
        if (
            claims.backendName != backendName ||
            claims.backendVersion != backendVersion ||
            claims.backendInstanceId != backendInstanceId
        ) {
            return rejected(SelectorHandleAuthority.Resolution.RejectionReason.WRONG_BACKEND)
        }
        if (!MessageDigest.isEqual(envelope.claimedTag, authenticationTag(envelope.payload))) {
            return rejected(SelectorHandleAuthority.Resolution.RejectionReason.TAMPERED)
        }
        if (claims.semanticGeneration != semanticGeneration()) {
            return rejected(SelectorHandleAuthority.Resolution.RejectionReason.STALE)
        }
        if (claims.familyMask and family.wireBit == 0) {
            return rejected(SelectorHandleAuthority.Resolution.RejectionReason.FAMILY_NOT_ALLOWED)
        }
        return SelectorHandleAuthority.Resolution.Resolved(claims.selector())
    }

    private fun encodeClaims(claims: Claims): ByteArray =
        CLAIMS_JSON.encodeToString(Claims.serializer(), claims).encodeToByteArray()

    private fun decodeEnvelope(value: String): DecodedEnvelope? = runCatching {
        val handle = SelectorHandle.parse(value)
        val envelope = Base64.getUrlDecoder().decode(handle.value.removePrefix(SelectorHandle.PREFIX))
        require(envelope.size > AUTHENTICATION_TAG_LENGTH) { "Selector handle payload is missing" }
        val payload = envelope.copyOfRange(0, envelope.size - AUTHENTICATION_TAG_LENGTH)
        val claimedTag = envelope.copyOfRange(envelope.size - AUTHENTICATION_TAG_LENGTH, envelope.size)
        val claims = CLAIMS_JSON.decodeFromString(
            Claims.serializer(),
            payload.decodeToString(throwOnInvalidSequence = true),
        )
        require(claims.semanticGeneration >= 0) { "Selector handle generation is invalid" }
        require(claims.selectorDeclarationStartOffset >= 0) { "Selector handle offset is invalid" }
        require(claims.familyMask and ALL_FAMILY_BITS == claims.familyMask) {
            "Selector handle family mask is invalid"
        }
        DecodedEnvelope(
            claims = claims,
            payload = payload,
            claimedTag = claimedTag,
        )
    }.getOrNull()

    private fun authenticationTag(payload: ByteArray): ByteArray =
        Mac.getInstance(AUTHENTICATION_ALGORITHM).run {
            init(SecretKeySpec(integrityKey, AUTHENTICATION_ALGORITHM))
            doFinal(payload)
        }

    private fun rejected(
        reason: SelectorHandleAuthority.Resolution.RejectionReason,
    ): SelectorHandleAuthority.Resolution = SelectorHandleAuthority.Resolution.Rejected(reason)

    @Serializable
    private data class Claims(
        val workspaceRoot: String,
        val backendName: String,
        val backendVersion: String,
        val backendInstanceId: String,
        val semanticGeneration: Long,
        val selectorFqName: String,
        val selectorDeclarationFile: String,
        val selectorDeclarationStartOffset: Int,
        val selectorKind: SymbolKind,
        val selectorContainingType: String?,
        val familyMask: Int,
    ) {
        fun selector(): KastExactSymbolSelector = KastExactSymbolSelector(
            fqName = selectorFqName,
            declarationFile = selectorDeclarationFile,
            declarationStartOffset = selectorDeclarationStartOffset,
            kind = selectorKind,
            containingType = selectorContainingType,
        )
    }

    private data class DecodedEnvelope(
        val claims: Claims,
        val payload: ByteArray,
        val claimedTag: ByteArray,
    )

    private companion object {
        const val AUTHENTICATION_ALGORITHM: String = "HmacSHA256"
        const val AUTHENTICATION_TAG_LENGTH: Int = 32
        const val INTEGRITY_KEY_LENGTH: Int = 32
        val CLAIMS_JSON: Json = Json
        val ALL_FAMILY_BITS: Int = SelectorOperationFamily.entries.fold(0) { mask, family ->
            mask or family.wireBit
        }

        fun randomIntegrityKey(): ByteArray = ByteArray(INTEGRITY_KEY_LENGTH).also { key ->
            SecureRandom().nextBytes(key)
        }
    }
}
