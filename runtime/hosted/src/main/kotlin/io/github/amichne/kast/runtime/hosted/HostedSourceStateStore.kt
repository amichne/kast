package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest as SourceReadRequestDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.evidenceBasis
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import java.util.concurrent.TimeUnit

internal enum class HostedOutputAcquisitionFailure {
    UNAVAILABLE,
    MISMATCH,
    IN_USE,
    CAPACITY_EXCEEDED,
}

internal sealed interface HostedSourceInput {
    data object Initial : HostedSourceInput

    data class Retained(val outcome: HostedSourceOutcome) : HostedSourceInput

    data class Published(val outcome: HostedSourceOutcome) : HostedSourceInput
}

/** One detached source owner stages native cursors, output suffixes and final immutable pages. */
internal class HostedSourceStateStore(
    private val limits: ReadLimits,
    internal val clock: () -> Long = System::nanoTime,
) {
    internal val binding = CanonicalOperationWireBindings.sourceRead
    internal val prefix = SOURCE_OUTPUT_PREFIX

    private fun normalize(request: SourceReadRequestDocument) =
        request.copy(
            page = SourceReadPageDocument.First,
            entityLimit = (SourceEntityLimitDocument.parse(1) as Refinement.Refined).value,
            textByteLimit = (SourceTextByteLimitDocument.parse(1) as Refinement.Refined).value,
            executionBudget = null,
        )

    internal fun normalizeOutcome(outcome: HostedSourceOutcome) = outcome.withSourceBudget(null)

    internal class Claim

    internal sealed interface Origin {
        data object Initial : Origin

        data class Retained(val token: ProtocolText) : Origin

        data class Replay(val token: ProtocolText) : Origin
    }

    internal sealed interface Execution {
        data object Ready : Execution

        data class Running(val claim: Claim, val reservation: Long) : Execution

        data class Published(
            val outcome: HostedSourceOutcome,
            val children: Set<ProtocolText>,
        ) : Execution
    }

    internal sealed interface Payload {
        data class Output(val outcome: HostedSourceOutcome) : Payload

        data class Cursor(val proof: SourceReadCursorProof) : Payload

        data object Consumed : Payload
    }

    internal data class Entry(
        val request: SourceReadRequestDocument,
        val lease: SemanticReadAuthority,
        val payload: Payload,
        val bytes: Long,
        val createdAt: Long,
        val owner: Claim?,
        val execution: Execution = Execution.Ready,
    )

    internal sealed interface Publication {
        data object Awaiting : Publication

        data class Prepared(val outcome: HostedSourceOutcome, val bytes: Long) : Publication

        data object Ended : Publication
    }

    internal enum class Lifetime {
        ACTIVE,
        RETIRED,
    }

    internal var lifetime = Lifetime.ACTIVE
    internal val entries = linkedMapOf<ProtocolText, Entry>()
    internal val attempts = linkedMapOf<Claim, HostedSourcePublicationSession>()
    private var highWaterBytes = 0L

    @Synchronized
    internal fun retentionMeasurements(): io.github.amichne.kast.query.protocol.QueryRetentionMeasurements =
        io.github.amichne.kast.query.protocol.QueryRetentionMeasurements(
            io.github.amichne.kast.query.protocol.QueryRetentionByteCount.parse(retainedBytes()).sourceRetentionValue(),
            io.github.amichne.kast.query.protocol.QueryRetentionByteCount.parse(highWaterBytes).sourceRetentionValue(),
            io.github.amichne.kast.query.contract.QueryCount.parse(entries.size + attempts.size).sourceRetentionValue(),
        )

    private val maximumBytes =
        minOf(
                limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value,
                limits[ReadLimitParameter.SOURCE_CONTINUATION_BYTES].value,
            )
            .toLong()
    private val capacity =
        minOf(
            limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value,
            limits[ReadLimitParameter.SOURCE_CONTINUATIONS].value,
        )
    internal val ttl =
        TimeUnit.MILLISECONDS.toNanos(
            minOf(
                    limits[ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS].value,
                    limits[ReadLimitParameter.SOURCE_CONTINUATION_TTL_MILLIS].value,
                )
                .toLong()
        )

    @Synchronized
    fun acquire(
        request: SourceReadRequestDocument,
        lease: SemanticReadAuthority,
        token: ProtocolText?,
        maximumPageBytes: Long,
        observe: (io.github.amichne.kast.query.protocol.QueryRetentionMeasurements) -> Unit = {},
    ): Refinement<HostedSourcePublicationSession, HostedOutputAcquisitionFailure> {
        if (lifetime == Lifetime.RETIRED) return rejected(HostedOutputAcquisitionFailure.UNAVAILABLE)
        expire()
        val normalized = normalize(request)
        val parent = token?.let(entries::get)
        when (val admitted = admitSourceParent(parent, normalized, lease, token)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        val published = parent?.execution as? Execution.Published
        val reserve =
            if (parent != null && published == null)
                maximumPageBytes.saturatedMultiply(RETAINED_DOCUMENT_FACTOR).saturatedAdd(PAGE_OVERHEAD)
            else 0L
        val protected = if (token == null) emptySet() else setOf(token) + published?.children.orEmpty()
        if (!makeCapacity(reserve.saturatedAdd(ATTEMPT_BYTES), extraEntries = 1, protected = protected))
            return rejected(HostedOutputAcquisitionFailure.CAPACITY_EXCEEDED)
        val claim = Claim()
        val origin = sourceOrigin(token, published)
        val input =
            when (val admitted = sourceInput(parent, published)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val session =
            HostedSourcePublicationSession(
                this,
                claim,
                origin,
                normalized,
                lease,
                parent?.createdAt ?: clock(),
                input,
                observe,
            )
        attempts[claim] = session
        if (parent != null && published == null)
            entries[token] =
                parent.copy(bytes = parent.bytes.saturatedAdd(reserve), execution = Execution.Running(claim, reserve))
        return Refinement.Refined(session)
    }

    private fun admitSourceParent(
        parent: Entry?,
        request: SourceReadRequestDocument,
        lease: SemanticReadAuthority,
        token: ProtocolText?,
    ): Refinement<Unit, HostedOutputAcquisitionFailure> {
        if (token != null && parent == null) return rejected(HostedOutputAcquisitionFailure.UNAVAILABLE)
        if (parent == null) return Refinement.Refined(Unit)
        if (expired(parent)) return rejected(HostedOutputAcquisitionFailure.UNAVAILABLE)
        if (parent.owner != null) return rejected(HostedOutputAcquisitionFailure.UNAVAILABLE)
        if (parent.lease != lease || parent.request != request) return rejected(HostedOutputAcquisitionFailure.MISMATCH)
        if (parent.execution is Execution.Running) return rejected(HostedOutputAcquisitionFailure.IN_USE)
        if (dependencyClosure(token) is Refinement.Rejected) return rejected(HostedOutputAcquisitionFailure.UNAVAILABLE)
        return Refinement.Refined(Unit)
    }

    private fun sourceOrigin(token: ProtocolText?, published: Execution.Published?): Origin =
        when {
            token == null -> Origin.Initial
            published != null -> Origin.Replay(token)
            else -> Origin.Retained(token)
        }

    private fun sourceInput(
        parent: Entry?,
        published: Execution.Published?,
    ): Refinement<HostedSourceInput, HostedOutputAcquisitionFailure> =
        when {
            parent == null -> Refinement.Refined(HostedSourceInput.Initial)
            published != null -> Refinement.Refined(HostedSourceInput.Published(published.outcome))
            parent.payload is Payload.Output -> Refinement.Refined(HostedSourceInput.Retained(parent.payload.outcome))
            parent.payload is Payload.Cursor -> Refinement.Refined(HostedSourceInput.Initial)
            else -> rejected(HostedOutputAcquisitionFailure.UNAVAILABLE)
        }

    @Synchronized
    fun clear() {
        entries.clear()
        attempts.clear()
    }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.RETIRED
        clear()
    }

    internal fun makeCapacity(bytes: Long, extraEntries: Int, protected: Set<ProtocolText> = emptySet()): Boolean {
        if (bytes > maximumBytes || extraEntries > capacity) return false
        while (entries.size + attempts.size + extraEntries > capacity || retainedBytes() > maximumBytes - bytes) {
            val victim = entries.keys.firstOrNull { it !in protected && evictable(it) } ?: return false
            entries.remove(victim)
        }
        return true
    }

    private fun evictable(token: ProtocolText): Boolean {
        val entry = entries[token] ?: return false
        if (entry.owner != null || entry.execution is Execution.Running) return false
        if (
            attempts.values.any { session ->
                when (val origin = session.origin) {
                    Origin.Initial -> token in session.children
                    is Origin.Retained -> token == origin.token || token in session.children
                    is Origin.Replay ->
                        token == origin.token ||
                            token in ((entries[origin.token]?.execution as? Execution.Published)?.children.orEmpty())
                }
            }
        )
            return false
        return entries.values.none { value ->
            token in ((value.execution as? Execution.Published)?.children.orEmpty()) ||
                (value.payload as? Payload.Output)?.outcome?.sourceContinuation() == token
        }
    }

    internal fun dependencyClosure(
        start: ProtocolText?,
        claim: Claim? = null,
    ): Refinement<Set<ProtocolText>, HostedPublicationFailureCause> {
        val closure =
            when (
                val retained =
                    if (start == null) Refinement.Refined(emptySet<ProtocolText>())
                    else sourceDependencyClosure(entries, start, clock(), ttl)
            ) {
                is Refinement.Refined -> retained.value
                is Refinement.Rejected -> return retained
            }
        if (claim != null) {
            val session = attempts[claim] ?: return Refinement.Rejected(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
            if (closure.any { entries[it]?.request != session.request || entries[it]?.lease != session.lease })
                return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
        }
        if (closure.any { entries[it]?.availableTo(claim) != true })
            return Refinement.Rejected(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)
        return Refinement.Refined(closure)
    }

    internal fun expired(entry: Entry, now: Long = clock()): Boolean = sourceEntryExpired(entry, now, ttl)

    internal fun expire() {
        val now = clock()
        val roots = linkedSetOf<ProtocolText>()
        attempts.values.forEach { session ->
            roots.addAll(session.children)
            when (val origin = session.origin) {
                Origin.Initial -> Unit
                is Origin.Retained -> roots.add(origin.token)
                is Origin.Replay -> roots.add(origin.token)
            }
        }
        sourceExpiredEntries(entries, roots, now, ttl).forEach(entries::remove)
    }

    private fun retainedBytes(): Long {
        val total =
            entries.values.fold(attempts.size.toLong() * ATTEMPT_BYTES) { sum, entry -> sum.saturatedAdd(entry.bytes) }
        highWaterBytes = maxOf(highWaterBytes, total)
        return total
    }

    private fun rejected(reason: HostedOutputAcquisitionFailure) = Refinement.Rejected(reason)
}

internal const val RETAINED_DOCUMENT_FACTOR = 4L

private fun <T> Refinement<T, *>.sourceRetentionValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Bounded source retention accounting cannot be negative")
    }

internal const val ATTEMPT_BYTES = 256L
internal const val PAGE_OVERHEAD = 4096L

internal fun Long.saturatedAdd(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

internal fun Long.saturatedMultiply(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

internal fun HostedSourceOutcome.sourceEntityCount(): Int =
    when (this) {
        is OperationOutcome.Complete -> evidence.payload.entities.values.size
        is OperationOutcome.Qualified -> evidence.payload.entities.values.size
        is OperationOutcome.Rejected -> 0
    }

internal fun HostedSourceOutcome.matchesSourceAuthority(lease: SemanticReadAuthority): Boolean =
    when (this) {
        is OperationOutcome.Complete ->
            evidence.operation == CanonicalOperation.SOURCE_READ.id && evidence.basis == lease.evidenceBasis()
        is OperationOutcome.Qualified ->
            evidence.operation == CanonicalOperation.SOURCE_READ.id && evidence.basis == lease.evidenceBasis()
        is OperationOutcome.Rejected -> true
    }

internal fun HostedSourceOutcome.sourceContinuation(): ProtocolText? =
    when (this) {
        is OperationOutcome.Qualified ->
            (qualification.continuation as? SourceReadContinuationStateDocument.Available)?.continuation
        is OperationOutcome.Complete,
        is OperationOutcome.Rejected -> null
    }
