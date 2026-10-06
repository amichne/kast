package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.query.protocol.QueryCheckpointExecution as CheckpointExecution
import io.github.amichne.kast.query.protocol.QueryProducerAcquisition as ProducerAcquisition
import io.github.amichne.kast.query.protocol.QueryStateEntry as Entry
import io.github.amichne.kast.query.protocol.QueryStateKey as Key
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID

/** Producer claims mutate the existing store maps only under the calling store's monitor and quota. */
internal class QueryStateAcquisition(
    private val entries: MutableMap<Key, Entry>,
    private val transientClaims: MutableMap<UUID, QueryExecutionClaim>,
    private val retention: QueryStateRetention,
    private val maximumBytes: Long,
    private val clock: () -> Long,
    private val ttlMillis: Long,
) {
    fun acquireProducer(
        key: Key,
        entry: Entry.Producer,
        token: QueryExecutionContinuation,
        lease: SemanticReadAuthority,
        maximumPageBytes: Long,
    ): ProducerAcquisition {
        if (key !in entries.liveDependencyClosure(clock(), ttlMillis)) return ProducerAcquisition.Unavailable
        when (val association = entry.lease.refineContinuationAuthority(lease)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return association.failure.acquisition
        }
        val pending =
            when (entry) {
                is Entry.Published -> {
                    if (entry.dependencies.any { it !in entries }) return ProducerAcquisition.Unavailable
                    if (!retention.evictFor(TRANSIENT_CLAIM_BASE_BYTES, setOf(key))) {
                        return ProducerAcquisition.CapacityExceeded
                    }
                    val claim =
                        QueryExecutionClaim(QueryExecutionOrigin.Replay(token, entry.page, clock()), UUID.randomUUID())
                    transientClaims[claim.identity] = claim
                    return ProducerAcquisition.Published(claim, entry.page)
                }
                is Entry.Pending -> entry
            }
        when (pending.execution) {
            is CheckpointExecution.Running -> return ProducerAcquisition.InUse
            CheckpointExecution.Ready -> Unit
        }
        if (pending.owner != null) return ProducerAcquisition.Unavailable
        val reserve =
            maximumPageBytes
                .saturatedMultiply(QUERY_PAGE_RESERVATION_MULTIPLIER)
                .saturatedAdd(QUERY_PAGE_RESERVATION_OVERHEAD)
        if (reserve > maximumBytes || !retention.reserveFor(reserve, setOf(key))) {
            return ProducerAcquisition.CapacityExceeded
        }
        val claim = QueryExecutionClaim(QueryExecutionOrigin.Producer(token), UUID.randomUUID())
        entries[key] =
            pending.withExecution(CheckpointExecution.Running(claim, reserve), pending.bytes.saturatedAdd(reserve))
        return ProducerAcquisition.Acquired(claim, pending)
    }
}
