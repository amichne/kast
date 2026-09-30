package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.source.contract.SourceReadCursorRetentionFailure
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import java.util.UUID

internal data class HostedSourceDetachedOutput(val outcome: HostedSourceOutcome, val bytes: Long)

internal fun HostedSourceStateStore.prepareSourceOutput(
    request: SourceReadRequest,
    lease: SemanticReadAuthority,
    outcome: HostedSourceOutcome,
    parent: HostedSourceStateStore.Payload.Output? = null,
): Refinement<HostedSourceDetachedOutput, HostedPublicationFailureCause> {
    val detached = normalizeOutcome(outcome)
    if (!detached.matchesSourceAuthority(lease))
        return Refinement.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
    if (parent != null && detached.sourceEntityCount() >= parent.outcome.sourceEntityCount())
        return Refinement.Rejected(HostedPublicationFailureCause.NON_ADVANCING_SUCCESSOR)
    val requestDocument =
        when (val encoded = binding.encodeRequest(request)) {
            is WireEncoding.Encoded -> encoded.document
            is WireEncoding.Rejected -> return Refinement.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
        }
    val outcomeDocument =
        when (val encoded = binding.encodeOutcome(detached)) {
            is WireEncoding.Encoded -> encoded.document
            is WireEncoding.Rejected -> return Refinement.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
        }
    val bytes =
        (requestDocument.toByteArray(Charsets.UTF_8).size.toLong() + outcomeDocument.toByteArray(Charsets.UTF_8).size)
            .saturatedMultiply(RETAINED_DOCUMENT_FACTOR)
    return Refinement.Refined(HostedSourceDetachedOutput(detached, bytes))
}

internal fun HostedSourceStateStore.newSourceOutputToken(): Refinement<ProtocolText, HostedPublicationFailureCause> =
    when (val parsed = ProtocolText.parse(prefix + UUID.randomUUID())) {
        is Refinement.Refined ->
            if (parsed.value in entries) Refinement.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
            else parsed
        is Refinement.Rejected -> Refinement.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
    }

internal fun HostedSourceStateStore.Entry.availableTo(claim: HostedSourceStateStore.Claim?) =
    owner == null || owner === claim

internal fun SourceReadCursorProof.admitSourceCursorSuccessor(
    lease: SemanticReadAuthority,
    previous: HostedSourceStateStore.Payload.Cursor?,
): Refinement<Unit, SourceReadCursorRetentionFailure> {
    if (snapshot.lease != lease) return Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
    if (previous == null) return Refinement.Refined(Unit)
    if (nextOrdinal.value < previous.proof.nextOrdinal.value)
        return Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
    if (traversal.revision <= previous.proof.traversal.revision)
        return Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
    if (!traversal.limitations.containsAll(previous.proof.traversal.limitations))
        return Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
    return Refinement.Refined(Unit)
}

internal data class HostedSourceIssuedCursor(val token: ProtocolText, val continuation: SourceReadContinuation)

internal fun ProtocolText.sourceContinuation(): Refinement<SourceReadContinuation, SourceReadCursorRetentionFailure> =
    when (val parsed = SourceReadContinuation.parse(value)) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected -> Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
    }

internal fun HostedSourceStateStore.newSourceCursorToken():
    Refinement<HostedSourceIssuedCursor, SourceReadCursorRetentionFailure> {
    val opaqueIdentity = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
    val continuation =
        when (val parsed = SourceReadContinuation.parse("source-read-continuation-v1|" + opaqueIdentity)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
        }
    val token =
        when (val parsed = ProtocolText.parse(continuation.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
        }
    if (token in entries) return Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE)
    return Refinement.Refined(HostedSourceIssuedCursor(token, continuation))
}
