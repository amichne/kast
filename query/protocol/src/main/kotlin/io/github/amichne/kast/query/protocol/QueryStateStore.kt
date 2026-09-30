package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.protocol.QueryCheckpointExecution as CheckpointExecution
import io.github.amichne.kast.query.protocol.QueryProducerAcquisition as ProducerAcquisition
import io.github.amichne.kast.query.protocol.QueryStateEntry as Entry
import io.github.amichne.kast.query.protocol.QueryStateKey as Key
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID

private const val ROW_REFERENCE_CHARGE_BYTES = 256L

/** One bounded project-owned lifetime for detached execution progress and immutable semantic rows. */
class QueryStateStore(
    private val capacity: Int = ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.defaultValue,
    private val maximumBytes: Long = ReadLimitParameter.QUERY_CONTINUATION_BYTES.defaultValue.toLong(),
    private val clock: () -> Long = System::nanoTime,
    private val ttlMillis: Long = ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS.defaultValue.toLong(),
) {
    /** Reserves bounded replay space before native work and isolates ownership to one token. */
    @Synchronized
    internal fun acquireCheckpoint(
        token: QueryExecutionContinuation.Pipeline,
        lease: SemanticReadAuthority,
        maximumPageBytes: Long,
    ): QueryCheckpointAcquisition {
        expire()
        val key = Key.Checkpoint(token)
        val entry = entries[key] as? Entry.Producer ?: return QueryCheckpointAcquisition.Unavailable
        val acquired = acquireProducer(key, entry, token, lease, maximumPageBytes)
        val result = acquired.checkpointAcquisition()
        if (result == QueryCheckpointAcquisition.Unavailable && acquired is ProducerAcquisition.Acquired)
            releasePublication(acquired.claim)
        return result
    }

    @Synchronized
    fun acquireOutput(
        token: QueryExecutionContinuation.Output,
        lease: SemanticReadAuthority,
        maximumPageBytes: Long,
    ): QueryOutputAcquisition {
        expire()
        val key = Key.Output(token)
        val entry = entries[key] as? Entry.Producer ?: return QueryOutputAcquisition.Unavailable
        val acquired = acquireProducer(key, entry, token, lease, maximumPageBytes)
        val result = acquired.outputAcquisition()
        if (result == QueryOutputAcquisition.Unavailable && acquired is ProducerAcquisition.Acquired)
            releasePublication(acquired.claim)
        return result
    }

    private fun acquireProducer(
        key: Key,
        entry: Entry.Producer,
        token: QueryExecutionContinuation,
        lease: SemanticReadAuthority,
        maximumPageBytes: Long,
    ): ProducerAcquisition {
        if (lifetime == Lifetime.RETIRED) return ProducerAcquisition.Unavailable
        if (key !in entries.liveDependencyClosure(clock(), ttlMillis)) return ProducerAcquisition.Unavailable
        if (entry.lease != lease) return ProducerAcquisition.Mismatch
        val pending =
            when (entry) {
                is Entry.Published -> {
                    if (entry.dependencies.any { it !in entries }) return ProducerAcquisition.Unavailable
                    if (!evictFor(TRANSIENT_CLAIM_BASE_BYTES, setOf(key))) {
                        return ProducerAcquisition.CapacityExceeded
                    }
                    val claim =
                        QueryExecutionClaim(QueryExecutionOrigin.Replay(token, entry.page, clock()), UUID.randomUUID())
                    transientClaims[claim.identity] = claim
                    retainedBytes()
                    return ProducerAcquisition.Published(claim, entry.page)
                }
                is Entry.Pending -> entry
            }
        when (pending.execution) {
            is CheckpointExecution.Running -> return ProducerAcquisition.InUse
            CheckpointExecution.Ready -> Unit
        }
        if (pending.owner != null) return ProducerAcquisition.Unavailable
        val reserve = maximumPageBytes.saturatedMultiply(4L).saturatedAdd(4096L)
        if (reserve > maximumBytes || !reserveFor(reserve, setOf(key))) {
            return ProducerAcquisition.CapacityExceeded
        }
        val claim = QueryExecutionClaim(QueryExecutionOrigin.Producer(token), UUID.randomUUID())
        entries[key] =
            pending.withExecution(CheckpointExecution.Running(claim, reserve), pending.bytes.saturatedAdd(reserve))
        retainedBytes()
        return ProducerAcquisition.Acquired(claim, pending)
    }

    /** The immutable page and its retained successor become visible in one store transition. */
    @Synchronized
    fun commitPublication(
        claim: QueryExecutionClaim,
        page: QueryPublishedPage,
        charge: QueryPublicationPageCharge = QueryPublicationPageCharge.Reserved,
    ): QueryPublicationCommit {
        if (lifetime == Lifetime.RETIRED) return QueryPublicationCommit.Rejected(QueryPublicationFailure.OWNER_RETIRED)
        expire()
        val transition =
            when (val admitted = entries.admitPublication(claim, page, charge, transientClaims, clock(), ttlMillis)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return QueryPublicationCommit.Rejected(admitted.failure)
            }
        when (transition) {
            QueryPublicationTransition.Initial -> {
                entries.publishReachable(claim, page)
                transientClaims.remove(claim.identity)
            }
            QueryPublicationTransition.Replay -> transientClaims.remove(claim.identity)
            is QueryPublicationTransition.Producer -> {
                entries[transition.key] = transition.entry
                entries.publishReachable(claim, page)
            }
        }
        return QueryPublicationCommit.Committed
    }

    /** Cancellation and rejected publication revoke only resources allocated by this attempt. */
    @Synchronized
    fun releasePublication(claim: QueryExecutionClaim) {
        entries.entries.removeIf { it.value.owner === claim }
        if (claim.origin !is QueryExecutionOrigin.Producer) {
            transientClaims.remove(claim.identity)
            expire()
            return
        }
        val key = claim.origin.token.key()
        val entry = entries[key] as? Entry.Pending ?: return
        val running = entry.execution as? CheckpointExecution.Running ?: return
        if (running.claim === claim) {
            entries[key] = entry.withExecution(CheckpointExecution.Ready, entry.bytes - running.reservedBytes)
        }
        expire()
    }

    private fun owns(claim: QueryExecutionClaim): Boolean = entries.ownsPublication(claim, transientClaims)

    private var lifetime = Lifetime.ACTIVE
    private val entries = linkedMapOf<Key, Entry>()
    private val transientClaims = linkedMapOf<UUID, QueryExecutionClaim>()
    private var highWaterBytes = 0L
    private val retention = QueryStateRetention(entries, transientClaims, capacity, maximumBytes)

    @Synchronized
    internal fun acquireInitial(
        lease: SemanticReadAuthority,
        protectedResults: Set<QueryResultReference> = emptySet(),
    ): QueryInitialAcquisition {
        if (lifetime == Lifetime.RETIRED) return QueryInitialAcquisition.Unavailable
        expire()
        val origin = entries.initialOrigin(lease, protectedResults, clock(), ttlMillis)
        if (!evictFor(origin.accountedClaimBytes(), entries.dependencyClosure(origin.inputs)))
            return QueryInitialAcquisition.CapacityExceeded
        val claim = QueryExecutionClaim(origin, UUID.randomUUID())
        transientClaims[claim.identity] = claim
        retainedBytes()
        return QueryInitialAcquisition.Acquired(claim)
    }

    @Synchronized
    fun retentionMeasurements(): QueryRetentionMeasurements =
        queryRetentionMeasurements(retainedBytes(), highWaterBytes, entries.size)

    @Synchronized
    fun issueCheckpoint(
        request: QueryRunRequest.Run,
        checkpoint: QueryCheckpoint,
        protectedResult: QueryResultReference? = null,
        publicationOwner: QueryExecutionClaim? = null,
    ): QueryCheckpointIssuance {
        if (lifetime == Lifetime.RETIRED || publicationOwner != null && !owns(publicationOwner))
            return QueryCheckpointIssuance.Unavailable
        expire()
        val normalized = request.copy(executionBudget = null)
        entries.checkpointFor(normalized, checkpoint, publicationOwner, clock(), ttlMillis)?.let {
            return QueryCheckpointIssuance.Issued(it)
        }
        val bytes = entryBytes(checkpoint.retainedBytes, normalized) ?: return QueryCheckpointIssuance.CapacityExceeded
        if (!evictFor(bytes, protectedResult?.let(Key::Result))) return QueryCheckpointIssuance.CapacityExceeded
        val token = generatedPipelineContinuation()
        entries[Key.Checkpoint(token)] = Entry.Checkpoint(normalized, checkpoint, clock(), bytes, publicationOwner)
        retainedBytes()
        return QueryCheckpointIssuance.Issued(token)
    }

    /** Read-result may reuse existing progress, but cannot republish a retired producer checkpoint. */
    @Synchronized
    fun retainedCheckpoint(request: QueryRunRequest.Run, checkpoint: QueryCheckpoint): QueryCheckpointIssuance {
        if (lifetime == Lifetime.RETIRED) return QueryCheckpointIssuance.Unavailable
        expire()
        val normalized = request.copy(executionBudget = null)
        val token =
            entries.checkpointFor(normalized, checkpoint, null, clock(), ttlMillis)
                ?: return QueryCheckpointIssuance.Unavailable
        return QueryCheckpointIssuance.Issued(token)
    }

    @Synchronized
    fun restoreCheckpoint(
        token: QueryExecutionContinuation.Pipeline,
        lease: SemanticReadAuthority,
    ): QueryCheckpointRestoration {
        expire()
        val entry = entries[Key.Checkpoint(token)] as? Entry.Checkpoint ?: return QueryCheckpointRestoration.Unavailable
        if (Key.Checkpoint(token) !in entries.liveDependencyClosure(clock(), ttlMillis))
            return QueryCheckpointRestoration.Unavailable
        if (entry.execution != CheckpointExecution.Ready) return QueryCheckpointRestoration.Unavailable
        if (entry.lease != lease) return QueryCheckpointRestoration.Mismatch
        return QueryCheckpointRestoration.Restored(entry.request, entry.checkpoint)
    }

    @Synchronized
    fun issueResult(
        request: QueryRunRequest.Run,
        result: QueryRetainedResult,
        protectedCheckpoint: QueryExecutionContinuation.Pipeline? = null,
        publicationOwner: QueryExecutionClaim? = null,
    ): QueryResultIssuance {
        if (lifetime == Lifetime.RETIRED || publicationOwner != null && !owns(publicationOwner))
            return QueryResultIssuance.Unavailable
        expire()
        val normalized = request.copy(executionBudget = null)
        val rowCount = result.rowCount
        val rowBytes = rowCount.toLong().saturatedMultiply(ROW_REFERENCE_CHARGE_BYTES)
        val bytes =
            entryBytes(result.retainedBytes.saturatedAdd(rowBytes), normalized)
                ?: return QueryResultIssuance.CapacityExceeded
        if (!evictFor(bytes, protectedCheckpoint?.let(Key::Checkpoint))) return QueryResultIssuance.CapacityExceeded
        val reference =
            when (val parsed = QueryResultReference.parse("result:v1:" + UUID.randomUUID())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> error("A generated result reference must satisfy its syntax")
            }
        val rowIds = java.util.Collections.unmodifiableList(List(rowCount) { generatedRowReference() })
        entries[Key.Result(reference)] = Entry.Result(normalized, result, rowIds, clock(), bytes, publicationOwner)
        retainedBytes()
        return QueryResultIssuance.Issued(reference, rowIds)
    }

    @Synchronized
    fun restoreResult(
        reference: QueryResultReference,
        lease: SemanticReadAuthority,
        publicationOwner: QueryExecutionClaim? = null,
    ): QueryResultRestoration {
        expire()
        val key = Key.Result(reference)
        val entry = entries[key] as? Entry.Result ?: return QueryResultRestoration.Unavailable
        if (entry.owner != null) return QueryResultRestoration.Unavailable
        if (!entries.inputAvailable(key, publicationOwner, transientClaims, clock(), ttlMillis))
            return QueryResultRestoration.Unavailable
        if (entry.lease != lease) return QueryResultRestoration.StaleBasis
        return QueryResultRestoration.Restored(entry.request, entry.result, entry.rowIds)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        transientClaims.clear()
    }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.RETIRED
        entries.clear()
        transientClaims.clear()
    }

    @Synchronized
    fun issueOutput(
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
        page: QueryPublishedPage,
        owner: QueryExecutionClaim,
        encodedBytes: QueryRetentionByteCount,
    ): QueryOutputIssuance {
        if (owner.origin is QueryExecutionOrigin.Replay) return QueryOutputIssuance.PublishedPageImmutable
        if (lifetime == Lifetime.RETIRED || !owns(owner)) return QueryOutputIssuance.Unavailable
        expire()
        val producerToken = (owner.origin as? QueryExecutionOrigin.Producer)?.token
        val parent = (producerToken?.key()?.let(entries::get) as? Entry.Output)?.page
        if (parent != null && page.itemCount() >= parent.itemCount()) return QueryOutputIssuance.NonAdvancing
        val normalized = request.normalized()
        entries.outputFor(normalized, lease, page, owner, clock(), ttlMillis)?.let {
            return QueryOutputIssuance.Issued(it)
        }
        val bytes = encodedBytes.value.saturatedMultiply(4L).saturatedAdd(normalized.accountedRequestBytes())
        if (bytes > maximumBytes || !evictFor(bytes, page.dependencies())) {
            return QueryOutputIssuance.CapacityExceeded
        }
        val token =
            QueryExecutionContinuation.Output.parse("query-output:v1:" + UUID.randomUUID()).let {
                (it as Refinement.Refined).value
            }
        entries[Key.Output(token)] = Entry.Output(normalized, page, lease, clock(), bytes, owner)
        retainedBytes()
        return QueryOutputIssuance.Issued(token)
    }

    private fun entryBytes(payloadBytes: Long, request: QueryRunRequest.Run): Long? {
        val requestBytes = request.accountedRequestBytes()
        if (capacity <= 0 || requestBytes !in 0..maximumBytes || payloadBytes !in 0..(maximumBytes - requestBytes)) {
            return null
        }
        return requestBytes + payloadBytes
    }

    private fun evictFor(bytes: Long, protected: Key?): Boolean = evictFor(bytes, setOfNotNull(protected))

    private fun evictFor(bytes: Long, protected: Set<Key>): Boolean = retention.evictFor(bytes, protected)

    private fun reserveFor(bytes: Long, protected: Set<Key>): Boolean = retention.reserveFor(bytes, protected)

    private fun retainedBytes(): Long {
        val total = retention.retainedBytes()
        highWaterBytes = maxOf(highWaterBytes, total)
        return total
    }

    /** Invoked only under this store's monitor; lifecycle helpers carry no independent storage or lock. */
    private fun expire() = retention.expire(clock(), ttlMillis)
}

private enum class Lifetime {
    ACTIVE,
    RETIRED,
}
