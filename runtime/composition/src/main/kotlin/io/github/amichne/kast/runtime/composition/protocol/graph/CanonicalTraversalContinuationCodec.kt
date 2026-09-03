package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.ExactSelectorIssuance
import io.github.amichne.kast.runtime.composition.protocol.ExactSelectorLookup
import io.github.amichne.kast.runtime.composition.protocol.RelationEndpointIssuance
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalContinuationFingerprint
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalPendingRead
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64

internal sealed interface CanonicalTraversalContinuationDecoding {
    data class Decoded(val continuation: TraversalContinuation) :
        CanonicalTraversalContinuationDecoding

    data object Malformed : CanonicalTraversalContinuationDecoding
}

@Serializable
private data class TraversalContinuationPayload(
    val start: String,
    val relation: String,
    val frontier: List<TraversalFrontierPayload>,
    val visited: List<String>,
    val terminalRelationLimitations: List<String> = emptyList(),
    val pending: TraversalPendingPayload? = null,
    val fingerprint: String,
)

@Serializable
private data class TraversalFrontierPayload(
    val selector: String,
    val depth: Int,
)

@Serializable
private sealed interface TraversalPendingPayload {
    @Serializable
    @SerialName("active")
    data class Active(
        val entry: TraversalFrontierPayload,
        val relationContinuation: String,
    ) : TraversalPendingPayload
}

private val traversalContinuationJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    classDiscriminator = "type"
}

/** Pure, bounded, self-contained transport for an exact traversal checkpoint. */
internal object CanonicalTraversalContinuationCodec {
    fun encode(
        continuation: TraversalContinuation,
        authority: CanonicalProtocolAuthority,
    ): TraversalContinuationDocument? {
        val start = when (val issued = authority.issueExact(continuation.start)) {
            is ExactSelectorIssuance.Issued -> issued.selector.value
            is ExactSelectorIssuance.Rejected -> return null
        }
        val frontier = continuation.checkpoint.frontier.map { entry ->
            entry.payload(authority) ?: return null
        }
        val pending = when (val state = continuation.checkpoint.pending) {
            TraversalPendingState.None -> null
            is TraversalPendingState.Active -> {
                val entry = state.read.entry.payload(authority) ?: return null
                val relation = CanonicalRelationContinuationCodec.encode(
                    state.read.relationContinuation,
                ) ?: return null
                TraversalPendingPayload.Active(entry, relation.value)
            }
        }
        val payload = traversalContinuationJson.encodeToString(
            TraversalContinuationPayload.serializer(),
            TraversalContinuationPayload(
                start = start,
                relation = continuation.meaning.tokenName(),
                frontier = frontier,
                visited = continuation.checkpoint.visited
                    .sortedBy(RelationEndpointFingerprint::value)
                    .map(RelationEndpointFingerprint::value),
                terminalRelationLimitations = continuation.checkpoint
                    .terminalRelationLimitations
                    .sortedBy { it.ordinal }
                    .map(RelationLimitation::name),
                pending = pending,
                fingerprint = continuation.fingerprint.value,
            ),
        ).toByteArray(Charsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        return TraversalContinuationDocument.parse(
            "traversal-continuation:v1:$encoded:${payload.sha256()}",
        ).refinedOrNull()
    }

    fun decode(
        document: TraversalContinuationDocument,
        budget: TraversalBudget,
        authority: CanonicalProtocolAuthority,
    ): CanonicalTraversalContinuationDecoding {
        val encoded = document.value.split(':').getOrNull(2)
            ?: return CanonicalTraversalContinuationDecoding.Malformed
        val payloadText = try {
            Base64.getUrlDecoder().decode(encoded).decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            return CanonicalTraversalContinuationDecoding.Malformed
        } catch (_: CharacterCodingException) {
            return CanonicalTraversalContinuationDecoding.Malformed
        }
        val payload = try {
            traversalContinuationJson.decodeFromString(
                TraversalContinuationPayload.serializer(),
                payloadText,
            )
        } catch (_: SerializationException) {
            return CanonicalTraversalContinuationDecoding.Malformed
        } catch (_: IllegalArgumentException) {
            return CanonicalTraversalContinuationDecoding.Malformed
        }
        val start = authority.exact(payload.start.protocolTextOrNull() ?: return malformed())
            .selectorOrNull() ?: return malformed()
        val meaning = payload.relation.relationMeaningOrNull() ?: return malformed()
        val plan = TraversalPlan.start(start, meaning, budget).refinedOrNull() ?: return malformed()
        val frontier = payload.frontier.map { entry ->
            entry.restore(plan, authority) ?: return malformed()
        }
        val visited = payload.visited.mapTo(linkedSetOf()) { raw ->
            RelationEndpointFingerprint.parse(raw).refinedOrNull() ?: return malformed()
        }
        if (visited.size != payload.visited.size) return malformed()
        val terminalRelationLimitations = payload.terminalRelationLimitations.mapTo(linkedSetOf()) {
            name -> RelationLimitation.entries.singleOrNull { it.name == name } ?: return malformed()
        }
        if (terminalRelationLimitations.size != payload.terminalRelationLimitations.size) {
            return malformed()
        }
        val pending = when (val state = payload.pending) {
            null -> TraversalPendingState.None
            is TraversalPendingPayload.Active -> {
                val entry = state.entry.restore(plan, authority) ?: return malformed()
                val relationDocument = io.github.amichne.kast.protocol.contract
                    .RelationContinuationDocument.parse(state.relationContinuation)
                    .refinedOrNull() ?: return malformed()
                val relation = when (
                    val decoded = CanonicalRelationContinuationCodec.decode(relationDocument)
                ) {
                    is CanonicalRelationContinuationDecoding.Decoded -> decoded.continuation
                    CanonicalRelationContinuationDecoding.Malformed -> return malformed()
                }
                val read = TraversalPendingRead.create(plan, entry, relation).refinedOrNull()
                    ?: return malformed()
                TraversalPendingState.active(read)
            }
        }
        val checkpoint = TraversalCheckpoint.create(
            plan,
            frontier,
            visited,
            pending,
            terminalRelationLimitations,
        )
            .refinedOrNull() ?: return malformed()
        val fingerprint = TraversalContinuationFingerprint.parse(payload.fingerprint)
            .refinedOrNull() ?: return malformed()
        val continuation = TraversalContinuation.restore(plan, checkpoint, fingerprint)
            .refinedOrNull() ?: return malformed()
        return CanonicalTraversalContinuationDecoding.Decoded(continuation)
    }
}

private fun TraversalFrontierEntry.payload(
    authority: CanonicalProtocolAuthority,
): TraversalFrontierPayload? {
    val selector = when (val issued = authority.issueEndpoint(node.endpoint)) {
        is RelationEndpointIssuance.Issued -> issued.selector.value
        is RelationEndpointIssuance.Rejected -> return null
    }
    return TraversalFrontierPayload(selector, depth.value)
}

private fun TraversalFrontierPayload.restore(
    plan: TraversalPlan,
    authority: CanonicalProtocolAuthority,
): TraversalFrontierEntry? {
    val selector = authority.exact(selector.protocolTextOrNull() ?: return null)
        .selectorOrNull() ?: return null
    val node = TraversalNode.restore(plan, selector).refinedOrNull() ?: return null
    val traversalDepth = TraversalDepth.parse(depth).refinedOrNull() ?: return null
    return TraversalFrontierEntry.create(plan, node, traversalDepth).refinedOrNull()
}

private fun ExactSelectorLookup.selectorOrNull() = when (this) {
    is ExactSelectorLookup.Found -> selector
    ExactSelectorLookup.Missing -> null
}

private fun String.protocolTextOrNull(): ProtocolText? = ProtocolText.parse(this).refinedOrNull()

private fun String.relationMeaningOrNull() =
    io.github.amichne.kast.relation.contract.RelationMeaning.all.singleOrNull {
        it.tokenName() == this
    }

private fun io.github.amichne.kast.relation.contract.RelationMeaning.tokenName(): String = when (this) {
    io.github.amichne.kast.relation.contract.RelationMeaning.References -> "references"
    io.github.amichne.kast.relation.contract.RelationMeaning.Callers -> "callers"
    io.github.amichne.kast.relation.contract.RelationMeaning.Callees -> "callees"
    io.github.amichne.kast.relation.contract.RelationMeaning.Implementations -> "implementations"
    io.github.amichne.kast.relation.contract.RelationMeaning.Inheritors -> "inheritors"
    io.github.amichne.kast.relation.contract.RelationMeaning.Overrides -> "overrides"
    io.github.amichne.kast.relation.contract.RelationMeaning.TypeUses -> "type-uses"
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private fun malformed(): CanonicalTraversalContinuationDecoding =
    CanonicalTraversalContinuationDecoding.Malformed

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
