package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

internal sealed interface QueryStateKey {
    data class Checkpoint(val token: QueryExecutionContinuation.Pipeline) : QueryStateKey

    data class Result(val reference: QueryResultReference) : QueryStateKey

    data class Output(val token: QueryExecutionContinuation.Output) : QueryStateKey
}

internal sealed interface QueryStateEntry {
    val lease: SemanticReadAuthority
    val createdAt: Long
    val bytes: Long
    val owner: QueryExecutionClaim?

    sealed interface Producer : QueryStateEntry {
        val request: QueryRunRequest
        val execution: QueryCheckpointExecution
    }

    sealed interface Pending : Producer {
        override val execution: QueryCheckpointExecution.Pending
    }

    data class Checkpoint(
        override val request: QueryRunRequest.Run,
        val checkpoint: QueryCheckpoint,
        override val createdAt: Long,
        override val bytes: Long,
        override val owner: QueryExecutionClaim? = null,
        override val execution: QueryCheckpointExecution.Pending = QueryCheckpointExecution.Ready,
    ) : Pending {
        override val lease: SemanticReadAuthority = checkpoint.lease
    }

    data class Output(
        override val request: QueryRunRequest,
        val page: QueryPublishedPage,
        override val lease: SemanticReadAuthority,
        override val createdAt: Long,
        override val bytes: Long,
        override val owner: QueryExecutionClaim? = null,
        override val execution: QueryCheckpointExecution.Pending = QueryCheckpointExecution.Ready,
    ) : Pending

    /** Consumed native/output work is superseded by this exact immutable publication and its dependencies. */
    data class Published(
        override val request: QueryRunRequest,
        override val lease: SemanticReadAuthority,
        val page: QueryPublishedPage,
        val dependencies: Set<QueryStateKey>,
        override val createdAt: Long,
        override val bytes: Long,
    ) : Producer {
        override val owner: QueryExecutionClaim? = null
        override val execution: QueryCheckpointExecution.Published =
            QueryCheckpointExecution.Published(page, dependencies)
    }

    data class Result(
        val request: QueryRunRequest.Run,
        val result: QueryRetainedResult,
        val rowIds: List<QueryResultRowReference>,
        override val createdAt: Long,
        override val bytes: Long,
        override val owner: QueryExecutionClaim? = null,
    ) : QueryStateEntry {
        override val lease: SemanticReadAuthority = result.lease
    }
}

internal sealed interface QueryCheckpointExecution {
    sealed interface Pending : QueryCheckpointExecution

    data object Ready : Pending

    data class Running(val claim: QueryExecutionClaim, val reservedBytes: Long) : Pending

    data class Published(val page: QueryPublishedPage, val dependencies: Set<QueryStateKey>) : QueryCheckpointExecution
}

/** A private child allocation requires the original active attempt, never a replay capability. */
internal fun Map<QueryStateKey, QueryStateEntry>.ownsPublication(
    claim: QueryExecutionClaim,
    transientClaims: Map<UUID, QueryExecutionClaim>,
): Boolean =
    when (val origin = claim.origin) {
        is QueryExecutionOrigin.Initial -> transientClaims[claim.identity] === claim
        is QueryExecutionOrigin.Replay -> false
        is QueryExecutionOrigin.Producer ->
            ((this[origin.token.key()] as? QueryStateEntry.Producer)?.execution as? QueryCheckpointExecution.Running)
                ?.claim === claim
    }

/** Requested inputs pin existing store keys only; absent references do not allocate retained identity state. */
internal fun Map<QueryStateKey, QueryStateEntry>.initialOrigin(
    lease: SemanticReadAuthority,
    requested: Set<QueryResultReference>,
    createdAt: Long,
    ttlMillis: Long,
): QueryExecutionOrigin.Initial =
    QueryExecutionOrigin.Initial(
        lease,
        createdAt,
        java.util.Set.copyOf(
            liveDependencyClosure(createdAt, ttlMillis).filterIsInstance<QueryStateKey.Result>().filter {
                it.reference in requested
            }
        ),
    )

/** An active claim preserves its admitted input; it cannot extend token admission to another request. */
internal fun Map<QueryStateKey, QueryStateEntry>.inputAvailable(
    key: QueryStateKey.Result,
    claim: QueryExecutionClaim?,
    transientClaims: Map<UUID, QueryExecutionClaim>,
    now: Long,
    ttlMillis: Long,
): Boolean {
    if (key in liveDependencyClosure(now, ttlMillis)) return true
    val origin = claim?.origin as? QueryExecutionOrigin.Initial ?: return false
    return ownsPublication(claim, transientClaims) &&
        origin.lease == this[key]?.lease &&
        key in origin.inputs &&
        now - origin.createdAt <= TimeUnit.MILLISECONDS.toNanos(ttlMillis)
}

internal sealed interface QueryProducerAcquisition {
    data class Acquired(val claim: QueryExecutionClaim, val entry: QueryStateEntry.Pending) : QueryProducerAcquisition

    data class Published(val claim: QueryExecutionClaim, val page: QueryPublishedPage) : QueryProducerAcquisition

    data object InUse : QueryProducerAcquisition

    data object Unavailable : QueryProducerAcquisition

    data object Mismatch : QueryProducerAcquisition

    data object CapacityExceeded : QueryProducerAcquisition
}

internal fun QueryProducerAcquisition.checkpointAcquisition(): QueryCheckpointAcquisition =
    when (this) {
        is QueryProducerAcquisition.Acquired ->
            when (val pending = entry) {
                is QueryStateEntry.Checkpoint ->
                    QueryCheckpointAcquisition.Acquired(claim, pending.request, pending.checkpoint)
                is QueryStateEntry.Output -> QueryCheckpointAcquisition.Unavailable
            }
        is QueryProducerAcquisition.Published -> QueryCheckpointAcquisition.Published(claim, page)
        QueryProducerAcquisition.InUse -> QueryCheckpointAcquisition.InUse
        QueryProducerAcquisition.Unavailable -> QueryCheckpointAcquisition.Unavailable
        QueryProducerAcquisition.Mismatch -> QueryCheckpointAcquisition.Mismatch
        QueryProducerAcquisition.CapacityExceeded -> QueryCheckpointAcquisition.CapacityExceeded
    }

internal fun QueryProducerAcquisition.outputAcquisition(): QueryOutputAcquisition =
    when (this) {
        is QueryProducerAcquisition.Acquired ->
            when (val pending = entry) {
                is QueryStateEntry.Output -> QueryOutputAcquisition.Acquired(claim, pending.page)
                is QueryStateEntry.Checkpoint -> QueryOutputAcquisition.Unavailable
            }
        is QueryProducerAcquisition.Published -> QueryOutputAcquisition.Published(claim, page)
        QueryProducerAcquisition.InUse -> QueryOutputAcquisition.InUse
        QueryProducerAcquisition.Unavailable -> QueryOutputAcquisition.Unavailable
        QueryProducerAcquisition.Mismatch -> QueryOutputAcquisition.Mismatch
        QueryProducerAcquisition.CapacityExceeded -> QueryOutputAcquisition.CapacityExceeded
    }

internal fun QueryPublishedPage.dependencies(): Set<QueryStateKey> = buildSet {
    val payload =
        when (val page = this@dependencies) {
            is OperationOutcome.Complete -> page.evidence.payload
            is OperationOutcome.Qualified -> {
                val progress = page.qualification.progress as? QueryQualifiedProgressDocument.Resumable
                progress?.checkpoint?.token?.let { add(it.key()) }
                page.evidence.payload
            }
            is OperationOutcome.Rejected -> return@buildSet
        }
    (payload.retention as? QueryResultRetention.Retained)?.reference?.let { add(QueryStateKey.Result(it)) }
}

internal fun QueryStateEntry.Pending.withExecution(
    execution: QueryCheckpointExecution.Pending,
    bytes: Long = this.bytes,
): QueryStateEntry.Pending =
    when (this) {
        is QueryStateEntry.Checkpoint -> copy(execution = execution, bytes = bytes)
        is QueryStateEntry.Output -> copy(execution = execution, bytes = bytes)
    }

internal fun QueryPublishedPage.itemCount(): Int =
    when (this) {
        is OperationOutcome.Complete -> evidence.payload.items.values.size
        is OperationOutcome.Qualified -> evidence.payload.items.values.size
        is OperationOutcome.Rejected -> 0
    }

internal fun QueryExecutionContinuation.key(): QueryStateKey =
    when (this) {
        is QueryExecutionContinuation.Pipeline -> QueryStateKey.Checkpoint(this)
        is QueryExecutionContinuation.Output -> QueryStateKey.Output(this)
    }

internal fun QueryStateEntry.dependencies(): Set<QueryStateKey> =
    when (this) {
        is QueryStateEntry.Result -> emptySet()
        is QueryStateEntry.Checkpoint -> emptySet()
        is QueryStateEntry.Output -> page.dependencies()
        is QueryStateEntry.Published -> dependencies
    }

internal fun Map<QueryStateKey, QueryStateEntry>.dependencyClosure(
    roots: Collection<QueryStateKey>
): Set<QueryStateKey> {
    val retained = mutableSetOf<QueryStateKey>()
    val pending = ArrayDeque<QueryStateKey>(roots)
    while (pending.isNotEmpty()) {
        val key = pending.removeFirst()
        if (retained.add(key)) this[key]?.dependencies()?.forEach(pending::addLast)
    }
    return retained
}

/** Token validity is capped by every original dependency age; physical claims do not renew it. */
internal fun Map<QueryStateKey, QueryStateEntry>.liveDependencyClosure(now: Long, ttlMillis: Long): Set<QueryStateKey> {
    val live = filterValues { it.owner == null && it.withinLifetime(now, ttlMillis) }.keys.toMutableSet()
    do {
        val removed = live.removeIf { key -> this.getValue(key).dependencies().any { it !in live } }
    } while (removed)
    return live
}

internal fun QueryStateEntry.withinLifetime(now: Long, ttlMillis: Long): Boolean =
    now - createdAt <= TimeUnit.MILLISECONDS.toNanos(ttlMillis)

internal fun QueryStateEntry?.hasActiveProducer(): Boolean =
    this is QueryStateEntry.Pending && execution is QueryCheckpointExecution.Running

internal fun Map<QueryStateKey, QueryStateEntry>.checkpointFor(
    request: QueryRunRequest.Run,
    checkpoint: QueryCheckpoint,
    owner: QueryExecutionClaim?,
    now: Long,
    ttlMillis: Long,
): QueryExecutionContinuation.Pipeline? =
    entries
        .firstOrNull { (key, entry) ->
            entry is QueryStateEntry.Checkpoint &&
                reusableCheckpoint(key, entry, owner, now, ttlMillis) &&
                entry.matchesCheckpoint(request, checkpoint)
        }
        ?.let { (it.key as QueryStateKey.Checkpoint).token }

private fun QueryStateEntry.Checkpoint.matchesCheckpoint(
    request: QueryRunRequest.Run,
    checkpoint: QueryCheckpoint,
): Boolean = this.request == request && this.checkpoint == checkpoint

internal fun Map<QueryStateKey, QueryStateEntry>.reusableCheckpoint(
    key: QueryStateKey,
    entry: QueryStateEntry,
    owner: QueryExecutionClaim?,
    now: Long,
    ttlMillis: Long,
): Boolean =
    entry is QueryStateEntry.Checkpoint &&
        entry.execution == QueryCheckpointExecution.Ready &&
        entry.withinLifetime(now, ttlMillis) &&
        (key in liveDependencyClosure(now, ttlMillis) || owner != null && entry.owner === owner)

internal fun generatedPipelineContinuation(): QueryExecutionContinuation.Pipeline =
    when (val parsed = QueryExecutionContinuation.Pipeline.parse("query:v1:" + UUID.randomUUID())) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A generated query continuation must satisfy its syntax")
    }

internal fun generatedRowReference(): QueryResultRowReference =
    when (val parsed = QueryResultRowReference.parse("result-row:v1:" + UUID.randomUUID())) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A generated query row reference must satisfy its syntax")
    }

internal fun Long.saturatedAdd(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

internal fun Long.saturatedMultiply(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

internal fun QueryRunRequest.normalized(): QueryRunRequest =
    when (this) {
        is QueryRunRequest.Run -> copy(executionBudget = null)
        is QueryRunRequest.Resume -> copy(executionBudget = null)
        is QueryRunRequest.ReadResult -> this
    }

internal fun QueryRunRequest.accountedRequestBytes(): Long =
    Json.encodeToString(QueryRunRequest.serializer(), this).toByteArray(Charsets.UTF_8).size.toLong() *
        RETAINED_ENCODING_MULTIPLIER

/** The store supplies its bounded totals; extraction cannot manufacture a semantic fact. */
internal fun queryRetentionMeasurements(retained: Long, highWater: Long, entryCount: Int): QueryRetentionMeasurements =
    QueryRetentionMeasurements(
        QueryRetentionByteCount.measured(retained),
        QueryRetentionByteCount.measured(highWater),
        (io.github.amichne.kast.query.contract.QueryCount.parse(entryCount) as Refinement.Refined).value,
    )

internal fun QueryPublishedPage.matchesAuthority(lease: SemanticReadAuthority): Boolean =
    when (this) {
        is OperationOutcome.Complete ->
            evidence.operation == CanonicalOperation.QUERY_RUN.id && evidence.basis == lease.evidenceBasis()
        is OperationOutcome.Qualified ->
            evidence.operation == CanonicalOperation.QUERY_RUN.id && evidence.basis == lease.evidenceBasis()
        is OperationOutcome.Rejected -> true
    }

internal fun MutableMap<QueryStateKey, QueryStateEntry>.publishReachable(
    claim: QueryExecutionClaim,
    page: QueryPublishedPage,
) {
    val reachable = dependencyClosure(page.dependencies())
    entries.removeIf { it.value.owner === claim && it.key !in reachable }
    replaceAll { _, value -> value.publishOwned(claim) }
}

internal const val RETAINED_ENCODING_MULTIPLIER = 4L

internal fun Map<QueryStateKey, QueryStateEntry>.outputFor(
    request: QueryRunRequest,
    lease: SemanticReadAuthority,
    page: QueryPublishedPage,
    owner: QueryExecutionClaim,
    now: Long,
    ttlMillis: Long,
): QueryExecutionContinuation.Output? {
    if (
        dependencyClosure(page.dependencies()).any { key ->
            val entry = this[key]
            !entry.admitsDependency(lease, owner) ||
                entry?.withinLifetime(now, ttlMillis) == false ||
                entry.hasActiveProducer()
        }
    )
        return null
    return entries
        .firstOrNull { (_, entry) ->
            entry is QueryStateEntry.Output &&
                entry.withinLifetime(now, ttlMillis) &&
                entry.execution == QueryCheckpointExecution.Ready &&
                entry.matchesOutput(request, lease, page, owner)
        }
        ?.key
        ?.let { (it as QueryStateKey.Output).token }
}

private fun QueryStateEntry.Output.matchesOutput(
    request: QueryRunRequest,
    lease: SemanticReadAuthority,
    page: QueryPublishedPage,
    owner: QueryExecutionClaim,
): Boolean {
    if (this.owner != null && this.owner !== owner) return false
    return this.lease == lease && this.request == request && this.page == page
}
