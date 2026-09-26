package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The existing encoded-suffix mechanism, parameterized by one canonical operation and its semantic identity. */
internal class HostedOutputPages<
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
>(
    private val binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
    private val prefix: String,
    private val limits: ReadLimits,
    private val normalize: (Request) -> Request,
    private val unavailable: Rejection,
    private val mismatch: Rejection,
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Entry<Request, Result, Qualification, Rejection>(
        val request: Request,
        val lease: SemanticReadAuthority,
        val outcome: OperationOutcome<Result, Qualification, Rejection>,
        val bytes: Long,
        val createdAt: Long,
    )

    private val entries = linkedMapOf<ProtocolText, Entry<Request, Result, Qualification, Rejection>>()
    private val maximumBytes = limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong()
    private val capacity = limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value

    @Synchronized
    fun issue(
        request: Request,
        lease: SemanticReadAuthority,
        outcome: OperationOutcome<Result, Qualification, Rejection>,
    ): HostedOutputRetention {
        expire()
        val normalized = normalize(request)
        // Bounded equality retains the first issued identity without hashing source-bearing evidence.
        val existing =
            entries.entries.firstOrNull { (_, entry) ->
                entry.lease == lease && entry.request == normalized && entry.outcome == outcome
            }
        if (existing != null) return HostedOutputRetention.Retained(existing.key)
        val requestDocument =
            when (val encoded = binding.encodeRequest(normalized)) {
                is WireEncoding.Encoded -> encoded.document
                is WireEncoding.Rejected -> return HostedOutputRetention.EncodingRejected
            }
        val outcomeDocument =
            when (val encoded = binding.encodeOutcome(outcome)) {
                is WireEncoding.Encoded -> encoded.document
                is WireEncoding.Rejected -> return HostedOutputRetention.EncodingRejected
            }
        val requestBytes = requestDocument.toByteArray(Charsets.UTF_8)
        val outcomeBytes = outcomeDocument.toByteArray(Charsets.UTF_8)
        val bytes = (requestBytes.size.toLong() + outcomeBytes.size) * RETAINED_DOCUMENT_FACTOR
        if (bytes > maximumBytes) return HostedOutputRetention.CapacityExceeded
        // Stable child identities make a lost reply replayable without consuming its parent checkpoint.
        val token =
            when (val parsed = ProtocolText.parse(prefix + UUID.randomUUID())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return HostedOutputRetention.EncodingRejected
            }
        if (entries.containsKey(token)) return HostedOutputRetention.EncodingRejected
        while (entries.size >= capacity || entries.values.sumOf { it.bytes } + bytes > maximumBytes) entries.remove(
            entries.keys.first()
        )
        entries[token] = Entry(normalized, lease, outcome, bytes, clock())
        return HostedOutputRetention.Retained(token)
    }

    @Synchronized
    fun restore(
        token: ProtocolText,
        request: Request,
        lease: SemanticReadAuthority,
    ): OperationOutcome<Result, Qualification, Rejection> {
        expire()
        val entry = entries[token] ?: return OperationOutcome.Rejected(unavailable)
        if (entry.lease != lease || normalize(request) != entry.request) return OperationOutcome.Rejected(mismatch)
        return entry.outcome
    }

    /** A typed resume action carries only the opaque cursor; the stored entry owns its request. */
    @Synchronized
    fun restore(token: ProtocolText, lease: SemanticReadAuthority): OperationOutcome<Result, Qualification, Rejection> {
        expire()
        val entry = entries[token] ?: return OperationOutcome.Rejected(unavailable)
        if (entry.lease != lease) return OperationOutcome.Rejected(mismatch)
        return entry.outcome
    }

    @Synchronized fun clear() = entries.clear()

    private fun expire() {
        val now = clock()
        entries.entries.removeIf {
            now - it.value.createdAt >
                TimeUnit.MILLISECONDS.toNanos(limits[ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS].value.toLong())
        }
    }
}

private const val RETAINED_DOCUMENT_FACTOR = 4L
