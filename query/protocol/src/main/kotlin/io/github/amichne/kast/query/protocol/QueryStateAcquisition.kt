package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.query.protocol.QueryCheckpointExecution as CheckpointExecution
import io.github.amichne.kast.query.protocol.QueryProducerAcquisition as ProducerAcquisition
import io.github.amichne.kast.query.protocol.QueryStateEntry as Entry
import io.github.amichne.kast.query.protocol.QueryStateKey as Key
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID

/** Claims use the existing store maps under its monitor. */
internal class QueryStateAcquisition(
    private val entries: MutableMap<Key, Entry>,
    private val transientClaims: MutableMap<UUID, QueryExecutionClaim>,
    private val retention: QueryStateRetention,
    private val maximumBytes: Long,
    private val clock: () -> Long,
    private val ttlMillis: Long,
) {
    fun acquireInitial(
        lease: SemanticReadAuthority,
        protectedResults: Set<QueryResultReference>,
        executionMode: QueryExecutionModeDocument,
    ): QueryInitialAcquisition {
        val origin = entries.initialOrigin(lease, protectedResults, clock(), ttlMillis)
        if (
            executionMode != QueryExecutionModeDocument.RUNNER_CONTROLLED &&
                !retention.evictFor(origin.accountedClaimBytes(), entries.dependencyClosure(origin.inputs))
        )
            return QueryInitialAcquisition.CapacityExceeded
        val claim = QueryExecutionClaim(origin, UUID.randomUUID(), executionMode)
        transientClaims[claim.identity] = claim
        return QueryInitialAcquisition.Acquired(claim)
    }

    fun acquireProducer(
        key: Key,
        entry: Entry.Producer,
        token: QueryExecutionContinuation,
        lease: SemanticReadAuthority,
        maximumPageBytes: Long,
        invocation: QueryInvocationPublication? = null,
    ): ProducerAcquisition {
        if (!entry.ownedByRunner(invocation) && key !in entries.liveDependencyClosure(clock(), ttlMillis))
            return ProducerAcquisition.Unavailable
        when (val association = entry.lease.refineContinuationAuthority(lease)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return association.failure.acquisition
        }
        val pending =
            when (entry) {
                is Entry.Published -> {
                    return acquireReplay(key, entry, token)
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
        if (
            !pending.request.runnerControlled() &&
                (reserve > maximumBytes || !retention.reserveFor(reserve, setOf(key)))
        ) {
            return ProducerAcquisition.CapacityExceeded
        }
        val claim =
            QueryExecutionClaim(
                QueryExecutionOrigin.Producer(token),
                UUID.randomUUID(),
                entry.request.executionMode(),
            )
        entries[key] =
            pending.withExecution(CheckpointExecution.Running(claim, reserve), pending.bytes.saturatedAdd(reserve))
        return ProducerAcquisition.Acquired(claim, pending)
    }

    private fun acquireReplay(
        key: Key,
        entry: Entry.Published,
        token: QueryExecutionContinuation,
    ): ProducerAcquisition {
        if (entry.dependencies.any { it !in entries }) return ProducerAcquisition.Unavailable
        if (!entry.request.runnerControlled() && !retention.evictFor(TRANSIENT_CLAIM_BASE_BYTES, setOf(key)))
            return ProducerAcquisition.CapacityExceeded
        val claim = QueryExecutionClaim(QueryExecutionOrigin.Replay(token, entry.page, clock()), UUID.randomUUID())
        transientClaims[claim.identity] = claim
        return ProducerAcquisition.Published(claim, entry.page)
    }

    fun releasePublication(claim: QueryExecutionClaim) {
        entries.entries.removeIf { it.value.owner === claim }
        if (claim.origin !is QueryExecutionOrigin.Producer) {
            transientClaims.remove(claim.identity)
            retention.expire(clock(), ttlMillis)
            return
        }
        val key = claim.origin.token.key()
        val entry = entries[key] as? Entry.Pending ?: return
        val running = entry.execution as? CheckpointExecution.Running ?: return
        if (running.claim === claim)
            entries[key] = entry.withExecution(CheckpointExecution.Ready, entry.bytes - running.reservedBytes)
        retention.expire(clock(), ttlMillis)
    }
}
