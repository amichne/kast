package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

sealed interface QueryCheckpointRestoration {
    data class Restored(val checkpoint: QueryCheckpoint) : QueryCheckpointRestoration

    data object Unavailable : QueryCheckpointRestoration

    data object Mismatch : QueryCheckpointRestoration
}

sealed interface QueryCheckpointIssuance {
    data class Issued(val token: ProtocolText) : QueryCheckpointIssuance

    data object CapacityExceeded : QueryCheckpointIssuance
}

/**
 * Bounded expiring host state. Only detached domain proofs enter this store; no service, PSI or project is retained.
 */
class QueryCheckpointStore(
    private val capacity: Int = ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.defaultValue,
    private val maximumBytes: Long = ReadLimitParameter.QUERY_CONTINUATION_BYTES.defaultValue.toLong(),
    private val clock: () -> Long = System::nanoTime,
    private val ttlMillis: Long = ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS.defaultValue.toLong(),
) {
    private data class Entry(
        val request: QueryRunRequest,
        val checkpoint: QueryCheckpoint,
        val createdAt: Long,
        val bytes: Long,
    )

    private val entries = linkedMapOf<ProtocolText, Entry>()

    @Synchronized
    fun issue(request: QueryRunRequest, checkpoint: QueryCheckpoint): QueryCheckpointIssuance {
        expire()
        val bytes =
            checkpoint.retainedBytes +
                Json.encodeToString(QueryRunRequest.serializer(), request).toByteArray(Charsets.UTF_8).size.toLong() *
                    RETAINED_TEXT_FACTOR
        if (capacity < 1 || bytes > maximumBytes) return QueryCheckpointIssuance.CapacityExceeded
        while (entries.size >= capacity || entries.values.sumOf { it.bytes } + bytes > maximumBytes) {
            entries.remove(entries.keys.first())
        }
        val token =
            when (val parsed = ProtocolText.parse("query:v1:" + UUID.randomUUID().toString())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> error("A generated query handle must fit ProtocolText")
            }
        entries[token] =
            Entry(
                request = request.copy(continuation = null, executionBudget = null),
                checkpoint = checkpoint,
                createdAt = clock(),
                bytes = bytes,
            )
        return QueryCheckpointIssuance.Issued(token)
    }

    @Synchronized
    fun restore(
        token: ProtocolText,
        request: QueryRunRequest,
        lease: SemanticReadAuthority,
    ): QueryCheckpointRestoration {
        expire()
        val entry = entries[token] ?: return QueryCheckpointRestoration.Unavailable
        if (
            entry.checkpoint.lease != lease ||
                request.copy(execution = entry.request.execution, continuation = null, executionBudget = null) != entry.request
        ) {
            return QueryCheckpointRestoration.Mismatch
        }
        return QueryCheckpointRestoration.Restored(entry.checkpoint)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun expire() {
        val now = clock()
        entries.entries.removeIf { now - it.value.createdAt > TimeUnit.MILLISECONDS.toNanos(ttlMillis) }
    }
}

private const val RETAINED_TEXT_FACTOR = 4L
