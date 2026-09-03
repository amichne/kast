package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationContinuationFingerprint
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderCursor
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationProviderPosition
import io.github.amichne.kast.relation.contract.RelationProviderPrefixDigest
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal sealed interface CanonicalRelationContinuationDecoding {
    data class Decoded(val continuation: RelationContinuation) :
        CanonicalRelationContinuationDecoding

    data object Malformed : CanonicalRelationContinuationDecoding
}

/** Pure self-contained codec for the public relation continuation capability. */
internal object CanonicalRelationContinuationCodec {
    fun encode(continuation: RelationContinuation): RelationContinuationDocument? {
        val payload = listOf(
            continuation.subject.value,
            continuation.meaning.tokenName(),
            continuation.scope.value,
            continuation.generation.value.toString(),
            continuation.nextProviderCursor.provider.name,
            continuation.nextProviderCursor.nextPosition.value.toString(),
            continuation.nextProviderCursor.consumedPrefixDigest.value,
            continuation.fingerprint.value,
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val raw = "relation-continuation:v1:$encoded:${payload.sha256()}"
        return RelationContinuationDocument.parse(raw).refinedOrNull()
    }

    fun decode(document: RelationContinuationDocument): CanonicalRelationContinuationDecoding {
        val encoded = document.value.split(':').getOrNull(2)
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val fields = try {
            Base64.getUrlDecoder().decode(encoded).toString(StandardCharsets.UTF_8).split('\n')
        } catch (_: IllegalArgumentException) {
            return CanonicalRelationContinuationDecoding.Malformed
        }
        if (fields.size != CONTINUATION_FIELD_COUNT) {
            return CanonicalRelationContinuationDecoding.Malformed
        }
        val subject = RelationEndpointFingerprint.parse(fields[0]).refinedOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val meaning = fields[1].relationMeaning()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val scope = RelationScopeFingerprint.parse(fields[2]).refinedOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val generationRaw = fields[3].toLongOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val generation = EvidenceGeneration.parse(generationRaw).refinedOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val provider = RelationProviderKind.entries.singleOrNull { it.name == fields[4] }
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val positionRaw = fields[5].toLongOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val position = RelationProviderPosition.parse(positionRaw).refinedOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val prefix = RelationProviderPrefixDigest.parse(fields[6]).refinedOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val fingerprint = RelationContinuationFingerprint.parse(fields[7]).refinedOrNull()
            ?: return CanonicalRelationContinuationDecoding.Malformed
        val cursor = RelationProviderCursor.restore(provider, position, prefix)
        return when (
            val restored = RelationContinuation.restore(
                subject,
                meaning,
                scope,
                generation,
                cursor,
                fingerprint,
            )
        ) {
            is Refinement.Refined -> CanonicalRelationContinuationDecoding.Decoded(restored.value)
            is Refinement.Rejected -> CanonicalRelationContinuationDecoding.Malformed
        }
    }
}

private fun RelationMeaning.tokenName(): String = when (this) {
    RelationMeaning.References -> "references"
    RelationMeaning.Callers -> "callers"
    RelationMeaning.Callees -> "callees"
    RelationMeaning.Implementations -> "implementations"
    RelationMeaning.Inheritors -> "inheritors"
    RelationMeaning.Overrides -> "overrides"
    RelationMeaning.TypeUses -> "type-uses"
}

private fun String.relationMeaning(): RelationMeaning? = when (this) {
    "references" -> RelationMeaning.References
    "callers" -> RelationMeaning.Callers
    "callees" -> RelationMeaning.Callees
    "implementations" -> RelationMeaning.Implementations
    "inheritors" -> RelationMeaning.Inheritors
    "overrides" -> RelationMeaning.Overrides
    "type-uses" -> RelationMeaning.TypeUses
    else -> null
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private const val CONTINUATION_FIELD_COUNT = 8
