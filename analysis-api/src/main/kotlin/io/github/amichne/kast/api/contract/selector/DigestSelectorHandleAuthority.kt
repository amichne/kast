package io.github.amichne.kast.api.contract.selector

import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
            selector = selector,
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
        return SelectorHandleAuthority.Resolution.Resolved(claims.selector)
    }

    private fun encodeClaims(claims: Claims): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(PAYLOAD_VERSION)
                output.writeText(claims.workspaceRoot)
                output.writeText(claims.backendName)
                output.writeText(claims.backendVersion)
                output.writeText(claims.backendInstanceId)
                output.writeLong(claims.semanticGeneration)
                output.writeText(claims.selector.fqName)
                output.writeText(claims.selector.declarationFile)
                output.writeInt(claims.selector.declarationStartOffset)
                output.writeText(requireNotNull(claims.selector.kind).name)
                output.writeNullableText(claims.selector.containingType)
                output.writeInt(claims.familyMask)
            }
            bytes.toByteArray()
        }

    private fun decodeEnvelope(value: String): DecodedEnvelope? = runCatching {
        val handle = SelectorHandle.parse(value)
        val envelope = Base64.getUrlDecoder().decode(handle.value.removePrefix(SelectorHandle.PREFIX))
        require(envelope.size > AUTHENTICATION_TAG_LENGTH) { "Selector handle payload is missing" }
        val payload = envelope.copyOfRange(0, envelope.size - AUTHENTICATION_TAG_LENGTH)
        val claimedTag = envelope.copyOfRange(envelope.size - AUTHENTICATION_TAG_LENGTH, envelope.size)
        val claims = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readUnsignedByte() == PAYLOAD_VERSION) { "Selector handle version is invalid" }
            val claims = Claims(
                workspaceRoot = input.readText(),
                backendName = input.readText(),
                backendVersion = input.readText(),
                backendInstanceId = input.readText(),
                semanticGeneration = input.readLong().also { generation ->
                    require(generation >= 0) { "Selector handle generation is invalid" }
                },
                selector = KastExactSymbolSelector(
                    fqName = input.readText(),
                    declarationFile = input.readText(),
                    declarationStartOffset = input.readInt().also { offset ->
                        require(offset >= 0) { "Selector handle offset is invalid" }
                    },
                    kind = SymbolKind.valueOf(input.readText()),
                    containingType = input.readNullableText(),
                ),
                familyMask = input.readInt().also { mask ->
                    require(mask and ALL_FAMILY_BITS == mask) { "Selector handle family mask is invalid" }
                },
            )
            require(input.available() == 0) { "Selector handle has trailing data" }
            claims
        }
        DecodedEnvelope(
            claims = claims,
            payload = payload,
            claimedTag = claimedTag,
        )
    }.getOrNull()

    private fun DataOutputStream.writeText(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_TEXT_BYTES) { "Selector handle text claim is too large" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableText(value: String?) {
        writeBoolean(value != null)
        if (value != null) {
            writeText(value)
        }
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        require(length in 0..MAX_TEXT_BYTES) { "Selector handle text length is invalid" }
        val encoded = readNBytes(length)
        require(encoded.size == length) { "Selector handle text claim is truncated" }
        val decoded = encoded.toString(StandardCharsets.UTF_8)
        require(decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(encoded)) {
            "Selector handle text is not canonical UTF-8"
        }
        return decoded
    }

    private fun DataInputStream.readNullableText(): String? =
        if (readBoolean()) readText() else null

    private fun authenticationTag(payload: ByteArray): ByteArray =
        Mac.getInstance(AUTHENTICATION_ALGORITHM).run {
            init(SecretKeySpec(integrityKey, AUTHENTICATION_ALGORITHM))
            doFinal(payload)
        }

    private fun rejected(
        reason: SelectorHandleAuthority.Resolution.RejectionReason,
    ): SelectorHandleAuthority.Resolution = SelectorHandleAuthority.Resolution.Rejected(reason)

    private data class Claims(
        val workspaceRoot: String,
        val backendName: String,
        val backendVersion: String,
        val backendInstanceId: String,
        val semanticGeneration: Long,
        val selector: KastExactSymbolSelector,
        val familyMask: Int,
    )

    private data class DecodedEnvelope(
        val claims: Claims,
        val payload: ByteArray,
        val claimedTag: ByteArray,
    )

    private companion object {
        const val PAYLOAD_VERSION: Int = 1
        const val AUTHENTICATION_ALGORITHM: String = "HmacSHA256"
        const val AUTHENTICATION_TAG_LENGTH: Int = 32
        const val INTEGRITY_KEY_LENGTH: Int = 32
        const val MAX_TEXT_BYTES: Int = 16_384
        val ALL_FAMILY_BITS: Int = SelectorOperationFamily.entries.fold(0) { mask, family ->
            mask or family.wireBit
        }

        fun randomIntegrityKey(): ByteArray = ByteArray(INTEGRITY_KEY_LENGTH).also { key ->
            SecureRandom().nextBytes(key)
        }
    }
}
