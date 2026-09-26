package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

private const val REQUEST_RETENTION_MULTIPLIER = 4L
private const val ROW_REFERENCE_CHARGE_BYTES = 256L

sealed interface QueryCheckpointRestoration {
    data class Restored(val request: QueryRunRequest.Run, val checkpoint: QueryCheckpoint) : QueryCheckpointRestoration

    data object Unavailable : QueryCheckpointRestoration

    data object Mismatch : QueryCheckpointRestoration
}

sealed interface QueryCheckpointIssuance {
    data class Issued(val token: QueryExecutionContinuation.Pipeline) : QueryCheckpointIssuance

    data object CapacityExceeded : QueryCheckpointIssuance
}

sealed interface QueryResultRestoration {
    data class Restored(
        val request: QueryRunRequest.Run,
        val result: QueryRetainedResult,
        val rowIds: List<QueryResultRowReference>,
    ) : QueryResultRestoration

    data object Unavailable : QueryResultRestoration

    data object StaleBasis : QueryResultRestoration
}

sealed interface QueryResultIssuance {
    data class Issued(val reference: QueryResultReference, val rowIds: List<QueryResultRowReference>) :
        QueryResultIssuance

    data object CapacityExceeded : QueryResultIssuance
}

/** One bounded project-owned lifetime for detached execution progress and immutable semantic rows. */
class QueryStateStore(
    private val capacity: Int = ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.defaultValue,
    private val maximumBytes: Long = ReadLimitParameter.QUERY_CONTINUATION_BYTES.defaultValue.toLong(),
    private val clock: () -> Long = System::nanoTime,
    private val ttlMillis: Long = ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS.defaultValue.toLong(),
) {
    private sealed interface Key {
        data class Checkpoint(val token: QueryExecutionContinuation.Pipeline) : Key

        data class Result(val reference: QueryResultReference) : Key
    }

    private sealed interface Entry {
        val lease: SemanticReadAuthority
        val createdAt: Long
        val bytes: Long

        data class Checkpoint(
            val request: QueryRunRequest.Run,
            val checkpoint: QueryCheckpoint,
            override val createdAt: Long,
            override val bytes: Long,
        ) : Entry {
            override val lease: SemanticReadAuthority = checkpoint.lease
        }

        data class Result(
            val request: QueryRunRequest.Run,
            val result: QueryRetainedResult,
            val rowIds: List<QueryResultRowReference>,
            override val createdAt: Long,
            override val bytes: Long,
        ) : Entry {
            override val lease: SemanticReadAuthority = result.lease
        }
    }

    private val entries = linkedMapOf<Key, Entry>()

    @Synchronized
    fun issueCheckpoint(
        request: QueryRunRequest.Run,
        checkpoint: QueryCheckpoint,
        protectedResult: QueryResultReference? = null,
    ): QueryCheckpointIssuance {
        expire()
        val normalized = request.copy(executionBudget = null)
        entries.entries
            .firstOrNull { (_, entry) ->
                entry is Entry.Checkpoint && entry.request == normalized && entry.checkpoint == checkpoint
            }
            ?.let {
                return QueryCheckpointIssuance.Issued((it.key as Key.Checkpoint).token)
            }
        val bytes = entryBytes(checkpoint.retainedBytes, normalized) ?: return QueryCheckpointIssuance.CapacityExceeded
        if (!evictFor(bytes, protectedResult?.let(Key::Result))) return QueryCheckpointIssuance.CapacityExceeded
        val token = generatedPipelineContinuation()
        entries[Key.Checkpoint(token)] = Entry.Checkpoint(normalized, checkpoint, clock(), bytes)
        return QueryCheckpointIssuance.Issued(token)
    }

    @Synchronized
    fun restoreCheckpoint(
        token: QueryExecutionContinuation.Pipeline,
        lease: SemanticReadAuthority,
    ): QueryCheckpointRestoration {
        expire()
        val entry = entries[Key.Checkpoint(token)] as? Entry.Checkpoint ?: return QueryCheckpointRestoration.Unavailable
        if (entry.lease != lease) return QueryCheckpointRestoration.Mismatch
        return QueryCheckpointRestoration.Restored(entry.request, entry.checkpoint)
    }

    @Synchronized
    fun issueResult(
        request: QueryRunRequest.Run,
        result: QueryRetainedResult,
        protectedCheckpoint: QueryExecutionContinuation.Pipeline? = null,
    ): QueryResultIssuance {
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
        entries[Key.Result(reference)] = Entry.Result(normalized, result, rowIds, clock(), bytes)
        return QueryResultIssuance.Issued(reference, rowIds)
    }

    @Synchronized
    fun restoreResult(reference: QueryResultReference, lease: SemanticReadAuthority): QueryResultRestoration {
        expire()
        val entry = entries[Key.Result(reference)] as? Entry.Result ?: return QueryResultRestoration.Unavailable
        if (entry.lease != lease) return QueryResultRestoration.StaleBasis
        return QueryResultRestoration.Restored(entry.request, entry.result, entry.rowIds)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun entryBytes(payloadBytes: Long, request: QueryRunRequest.Run): Long? {
        val requestBytes = requestBytes(request)
        if (capacity <= 0 || requestBytes !in 0..maximumBytes || payloadBytes !in 0..(maximumBytes - requestBytes)) {
            return null
        }
        return requestBytes + payloadBytes
    }

    private fun evictFor(bytes: Long, protected: Key?): Boolean {
        while (entries.size >= capacity || retainedBytes() > maximumBytes - bytes) {
            val victim = entries.keys.firstOrNull { it != protected } ?: return false
            entries.remove(victim)
        }
        return true
    }

    private fun retainedBytes(): Long {
        var total = 0L
        for (entry in entries.values) {
            if (entry.bytes > maximumBytes - total) return maximumBytes
            total += entry.bytes
        }
        return total
    }

    private fun requestBytes(request: QueryRunRequest.Run): Long =
        Json.encodeToString(QueryRunRequest.serializer(), request).toByteArray(Charsets.UTF_8).size.toLong() *
            REQUEST_RETENTION_MULTIPLIER

    private fun expire() {
        val now = clock()
        entries.entries.removeIf { now - it.value.createdAt > TimeUnit.MILLISECONDS.toNanos(ttlMillis) }
    }
}

private fun generatedPipelineContinuation(): QueryExecutionContinuation.Pipeline =
    when (val parsed = QueryExecutionContinuation.Pipeline.parse("query:v1:" + UUID.randomUUID())) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A generated query continuation must satisfy its syntax")
    }

private fun generatedRowReference(): QueryResultRowReference =
    when (val parsed = QueryResultRowReference.parse("result-row:v1:" + UUID.randomUUID())) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A generated query row reference must satisfy its syntax")
    }

private fun Long.saturatedAdd(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatedMultiply(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
