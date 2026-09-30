package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceReadRequest as SourceReadRequestDocument
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Claim
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Entry
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Execution
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Lifetime
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Origin
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Payload
import io.github.amichne.kast.runtime.hosted.HostedSourceStateStore.Publication
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadContinuationPort
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.source.contract.SourceReadCursorRetentionFailure
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadPublicationEffect

/** The capability remains request-local; only detached entries survive its final publication. */
internal class HostedSourcePublicationSession(
    private val store: HostedSourceStateStore,
    internal val claim: Claim,
    internal val origin: Origin,
    internal val request: SourceReadRequestDocument,
    internal val lease: SemanticReadAuthority,
    internal val createdAt: Long,
    val input: HostedSourceInput,
    private val observe: (io.github.amichne.kast.query.protocol.QueryRetentionMeasurements) -> Unit,
) : HostedReadPublicationEffect, SourceReadContinuationPort {
    private var publication: Publication = Publication.Awaiting
    internal val children = linkedSetOf<ProtocolText>()

    fun issue(outcome: HostedSourceOutcome): HostedOutputRetention =
        synchronized(store) {
            when (val admitted = admitAttempt()) {
                is Refinement.Rejected -> return@synchronized HostedOutputRetention.Rejected(admitted.failure)
                is Refinement.Refined -> Unit
            }
            store.expire()
            if (origin is Origin.Replay) return@synchronized HostedOutputRetention.CapacityExceeded
            val parent = (origin as? Origin.Retained)?.token?.let(store.entries::get)?.payload as? Payload.Output
            val detached =
                when (val prepared = store.prepareSourceOutput(request, lease, outcome, parent)) {
                    is Refinement.Refined -> prepared.value
                    is Refinement.Rejected -> return@synchronized HostedOutputRetention.Rejected(prepared.failure)
                }
            retainOutput(detached)
        }

    private fun retainOutput(detached: HostedSourceDetachedOutput): HostedOutputRetention {
        when (val dependencies = store.dependencyClosure(detached.outcome.sourceContinuation(), claim)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return HostedOutputRetention.Rejected(dependencies.failure)
        }
        val existing = matchingEntry { (it.payload as? Payload.Output)?.outcome == detached.outcome }
        if (existing != null) {
            children += existing.key
            return HostedOutputRetention.Retained(existing.key)
        }
        if (!store.makeCapacity(detached.bytes, extraEntries = 1)) return HostedOutputRetention.CapacityExceeded
        val token =
            when (val parsed = store.newSourceOutputToken()) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return HostedOutputRetention.Rejected(parsed.failure)
            }
        store.entries[token] = Entry(request, lease, Payload.Output(detached.outcome), detached.bytes, createdAt, claim)
        children += token
        return HostedOutputRetention.Retained(token)
    }

    override fun admit(
        context: SourceReadContext,
        request: SourceReadRequest,
    ): Refinement<SourceReadEntityCursor, SourceReadRejection> =
        synchronized(store) {
            when (admitAttempt()) {
                is Refinement.Rejected ->
                    return@synchronized Refinement.Rejected(SourceReadRejection.SOURCE_UNAVAILABLE)
                is Refinement.Refined -> Unit
            }
            if (context.lease != lease)
                return@synchronized Refinement.Rejected(SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH)
            when (val page = request.page) {
                SourceReadPage.First -> Refinement.Refined(SourceReadEntityCursor.First)
                is SourceReadPage.Continue -> restoreCursor(context, request, page.continuation)
            }
        }

    private fun restoreCursor(
        context: SourceReadContext,
        request: SourceReadRequest,
        continuation: SourceReadContinuation,
    ): Refinement<SourceReadEntityCursor, SourceReadRejection> {
        val token =
            when (val parsed = ProtocolText.parse(continuation.value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            }
        val entry = store.entries[token] ?: return Refinement.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE)
        if (store.expired(entry) || entry.owner != null && entry.owner !== claim)
            return Refinement.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE)
        val running = entry.execution as? Execution.Running
        if (running != null && running.claim !== claim)
            return Refinement.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE)
        val proof =
            (entry.payload as? Payload.Cursor)?.proof
                ?: return Refinement.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE)
        return proof.admit(context, request)
    }

    override fun issue(
        proof: SourceReadCursorProof
    ): Refinement<SourceReadContinuation, SourceReadCursorRetentionFailure> =
        synchronized(store) {
            when (admitAttempt()) {
                is Refinement.Rejected ->
                    return@synchronized Refinement.Rejected(SourceReadCursorRetentionFailure.OWNER_UNAVAILABLE)
                is Refinement.Refined -> Unit
            }
            store.expire()
            val previous = (origin as? Origin.Retained)?.token?.let(store.entries::get)?.payload as? Payload.Cursor
            when (val admitted = proof.admitSourceCursorSuccessor(lease, previous)) {
                is Refinement.Rejected -> return@synchronized admitted
                is Refinement.Refined -> Unit
            }
            retainCursor(proof)
        }

    private fun retainCursor(
        proof: SourceReadCursorProof
    ): Refinement<SourceReadContinuation, SourceReadCursorRetentionFailure> {
        val existing = matchingEntry { (it.payload as? Payload.Cursor)?.proof?.samePosition(proof) == true }
        if (existing != null) {
            children += existing.key
            return existing.key.sourceContinuation()
        }
        if (!store.makeCapacity(proof.retainedBytes, extraEntries = 1))
            return Refinement.Rejected(SourceReadCursorRetentionFailure.CAPACITY_EXCEEDED)
        val issued =
            when (val created = store.newSourceCursorToken()) {
                is Refinement.Refined -> created.value
                is Refinement.Rejected -> return created
            }
        store.entries[issued.token] =
            Entry(request, lease, Payload.Cursor(proof), proof.retainedBytes, createdAt, claim)
        children += issued.token
        return Refinement.Refined(issued.continuation)
    }

    private fun matchingEntry(accept: (Entry) -> Boolean) =
        store.entries.entries.firstOrNull { (token, entry) ->
            entry.availableTo(claim) &&
                entry.execution !is Execution.Running &&
                entry.lease == lease &&
                entry.request == request &&
                accept(entry) &&
                store.dependencyClosure(token, claim) is Refinement.Refined
        }

    /** Parse the exact operation binding after fitting; publication retains that immutable detached page. */
    fun finish(response: HostedResponse): Refinement<Unit, HostedQueryFailure> =
        synchronized(store) {
            if (store.attempts[claim] !== this || publication != Publication.Awaiting)
                return@synchronized publicationRejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
            when (val decoded = store.binding.decodeOutcome(response.document)) {
                is WireDecoding.Decoded -> {
                    val detached =
                        when (val prepared = store.prepareSourceOutput(request, lease, decoded.value)) {
                            is Refinement.Refined -> prepared.value
                            is Refinement.Rejected -> return@synchronized publicationRejected(prepared.failure)
                        }
                    publication = Publication.Prepared(detached.outcome, detached.bytes)
                    observe(store.retentionMeasurements())
                    Refinement.Refined(Unit)
                }
                is WireDecoding.Rejected -> publicationRejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
            }
        }

    override fun commit(): Refinement<Unit, HostedQueryFailure> =
        synchronized(store) {
            val ready =
                when (val admitted = admitPreparedPage()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return@synchronized publicationRejected(admitted.failure)
                }
            publishPrepared(ready)
        }

    private fun publishPrepared(ready: Publication.Prepared): Refinement<Unit, HostedQueryFailure> {
        val advertised = ready.outcome.sourceContinuation()
        val dependencies =
            when (val preserved = store.dependencyClosure(advertised, claim)) {
                is Refinement.Refined -> preserved.value
                is Refinement.Rejected -> return publicationRejected(preserved.failure)
            }
        if (advertised != null && origin is Origin.Retained && advertised == origin.token)
            return publicationRejected(HostedPublicationFailureCause.NON_ADVANCING_SUCCESSOR)
        when (val committed = commitOrigin(ready, dependencies)) {
            is Refinement.Rejected -> return publicationRejected(committed.failure)
            is Refinement.Refined -> Unit
        }
        completePublication(dependencies)
        return Refinement.Refined(Unit)
    }

    private fun completePublication(dependencies: Set<ProtocolText>) {
        store.entries.entries.removeIf { it.value.owner === claim && it.key !in dependencies }
        store.entries.replaceAll { _, value -> if (value.owner === claim) value.copy(owner = null) else value }
        publication = Publication.Ended
        store.attempts.remove(claim)
        observe(store.retentionMeasurements())
    }

    private fun admitAttempt(): Refinement<Unit, HostedPublicationFailureCause> {
        if (store.lifetime == Lifetime.RETIRED) return Refinement.Rejected(HostedPublicationFailureCause.OWNER_RETIRED)
        if (store.attempts[claim] !== this) return Refinement.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        if (publication != Publication.Awaiting)
            return Refinement.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        if (store.clock() - createdAt >= store.ttl) return Refinement.Rejected(HostedPublicationFailureCause.EXPIRED)
        return Refinement.Refined(Unit)
    }

    private fun admitPreparedPage(): Refinement<Publication.Prepared, HostedPublicationFailureCause> {
        if (store.lifetime == Lifetime.RETIRED) return Refinement.Rejected(HostedPublicationFailureCause.OWNER_RETIRED)
        if (store.attempts[claim] !== this) return Refinement.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        if (store.clock() - createdAt >= store.ttl) return Refinement.Rejected(HostedPublicationFailureCause.EXPIRED)
        val ready =
            publication as? Publication.Prepared
                ?: return Refinement.Rejected(HostedPublicationFailureCause.INVALID_FITTED_PAGE)
        return Refinement.Refined(ready)
    }

    private fun commitOrigin(
        ready: Publication.Prepared,
        dependencies: Set<ProtocolText>,
    ): Refinement<Unit, HostedPublicationFailureCause> =
        when (val selected = origin) {
            Origin.Initial -> Refinement.Refined(Unit)
            is Origin.Retained -> commitRetained(selected.token, ready, dependencies)
            is Origin.Replay -> verifyReplay(selected.token, ready)
        }

    private fun commitRetained(
        token: ProtocolText,
        ready: Publication.Prepared,
        dependencies: Set<ProtocolText>,
    ): Refinement<Unit, HostedPublicationFailureCause> {
        val parent =
            store.entries[token] ?: return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
        val running =
            parent.execution as? Execution.Running
                ?: return Refinement.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        if (running.claim !== claim) return Refinement.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        if (!store.makeCapacity((ready.bytes - parent.bytes).coerceAtLeast(0), extraEntries = 0))
            return Refinement.Rejected(HostedPublicationFailureCause.CAPACITY_EXCEEDED)
        store.entries[token] =
            parent.copy(
                payload = Payload.Consumed,
                bytes = ready.bytes,
                execution = Execution.Published(ready.outcome, dependencies),
            )
        return Refinement.Refined(Unit)
    }

    private fun verifyReplay(
        token: ProtocolText,
        ready: Publication.Prepared,
    ): Refinement<Unit, HostedPublicationFailureCause> {
        val published =
            store.entries[token]?.execution as? Execution.Published
                ?: return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
        if (published.outcome != ready.outcome)
            return Refinement.Rejected(HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH)
        if (published.children.any { it !in store.entries })
            return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
        return Refinement.Refined(Unit)
    }

    override fun discard() =
        synchronized(store) {
            store.entries.entries.removeIf { it.value.owner === claim }
            val selected = origin
            if (selected is Origin.Retained) {
                val parent = store.entries[selected.token]
                val running = parent?.execution as? Execution.Running
                if (parent != null && running?.claim === claim)
                    store.entries[selected.token] =
                        parent.copy(bytes = parent.bytes - running.reservation, execution = Execution.Ready)
            }
            publication = Publication.Ended
            store.attempts.remove(claim)
            store.expire()
            observe(store.retentionMeasurements())
        }
}

private fun publicationRejected(cause: HostedPublicationFailureCause) =
    Refinement.Rejected(HostedQueryFailure.Publication(cause))
