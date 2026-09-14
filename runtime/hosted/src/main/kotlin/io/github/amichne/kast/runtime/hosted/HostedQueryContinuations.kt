package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.query.protocol.QueryCheckpointStore
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID
import java.util.concurrent.TimeUnit

internal typealias HostedQueryOutcome = OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunRejection>

/** Project-free bounded detached state shared across hosted read epochs; lease checks fail closed on every resume. */
internal sealed interface HostedQueryRetention {
    data class Retained(val token: ProtocolText) : HostedQueryRetention

    data object CapacityExceeded : HostedQueryRetention

    data object EncodingRejected : HostedQueryRetention
}

@Service(Service.Level.PROJECT)
internal class HostedQueryContinuations : Disposable {
    private var active: Active? = null

    @Synchronized
    fun forEpoch(lease: SemanticReadAuthority, limits: ReadLimits): Active {
        val current = active
        if (current != null && current.lease == lease) return current
        current?.clear()
        return Active(lease, limits).also { active = it }
    }

    @Synchronized
    override fun dispose() {
        active?.clear()
        active = null
    }

    companion object {
        const val prefix = "query-output:v1:"
    }

    class Active(val lease: SemanticReadAuthority, private val limits: ReadLimits) {
        val checkpoints =
            QueryCheckpointStore(
                capacity = limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value,
                maximumBytes = limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong(),
                ttlMillis = limits[ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS].value.toLong(),
            )

        private data class Entry(
            val request: QueryRunRequest,
            val lease: SemanticReadAuthority,
            val outcome: HostedQueryOutcome,
            val bytes: Long,
            val createdAt: Long,
        )

        private val entries = linkedMapOf<ProtocolText, Entry>()
        private val maximumBytes = limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong()
        private val capacity = limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value

        @Synchronized
        fun issue(
            request: QueryRunRequest,
            lease: SemanticReadAuthority,
            outcome: HostedQueryOutcome,
        ): HostedQueryRetention {
            expire()
            val bytes =
                when (val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(outcome)) {
                    is WireEncoding.Encoded -> encoded.document.toByteArray(Charsets.UTF_8).size.toLong() * 4L
                    is WireEncoding.Rejected -> return HostedQueryRetention.EncodingRejected
                }
            if (bytes > maximumBytes) return HostedQueryRetention.CapacityExceeded
            while (entries.size >= capacity || entries.values.sumOf { it.bytes } + bytes > maximumBytes) entries.remove(
                entries.keys.first()
            )
            val token =
                when (val parsed = ProtocolText.parse(prefix + UUID.randomUUID())) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> error("Generated query output handle must fit ProtocolText")
                }
            entries[token] =
                Entry(
                    request = request.copy(continuation = null),
                    lease = lease,
                    outcome = outcome,
                    bytes = bytes,
                    createdAt = System.nanoTime(),
                )
            return HostedQueryRetention.Retained(token)
        }

        @Synchronized
        fun restore(token: ProtocolText, request: QueryRunRequest, lease: SemanticReadAuthority): HostedQueryOutcome {
            expire()
            val entry = entries[token] ?: return rejection(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
            if (
                entry.lease != lease ||
                    request.copy(execution = entry.request.execution, continuation = null) != entry.request
            ) {
                return rejection(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH)
            }
            return entry.outcome
        }

        @Synchronized
        fun clear() {
            entries.clear()
            checkpoints.clear()
        }

        private fun expire() {
            val now = System.nanoTime()
            entries.entries.removeIf { entry ->
                now - entry.value.createdAt >
                    TimeUnit.MILLISECONDS.toNanos(
                        limits[ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS].value.toLong()
                    )
            }
        }

        private fun rejection(reason: QueryExecutionRejectionDocument): HostedQueryOutcome =
            OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
    }
}
