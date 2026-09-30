package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.DiagnosticStateAttempt as Attempt
import io.github.amichne.kast.query.protocol.DiagnosticStateEntry as Entry
import io.github.amichne.kast.query.protocol.DiagnosticStateExecution as Execution
import io.github.amichne.kast.query.protocol.DiagnosticStateInput as Input
import io.github.amichne.kast.query.protocol.DiagnosticStateKey as Key
import io.github.amichne.kast.query.protocol.DiagnosticStateLifetime as Lifetime
import java.util.concurrent.TimeUnit

/** One detached quota owns scan checkpoints, fitted output suffixes and immutable published pages. */
class DiagnosticCheckpointStore(
    internal val capacity: Int = ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.defaultValue,
    internal val maximumBytes: Long = ReadLimitParameter.QUERY_CONTINUATION_BYTES.defaultValue.toLong(),
    private val maximumCheckpointBytes: Long = ReadLimitParameter.QUERY_CHECKPOINT_BYTES.defaultValue.toLong(),
    ttlMillis: Long = ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS.defaultValue.toLong(),
    internal val clock: () -> Long = System::nanoTime,
) {
    internal var lifetime = Lifetime.ACTIVE
    internal val ttl = TimeUnit.MILLISECONDS.toNanos(ttlMillis)
    internal val entries = linkedMapOf<Key, Entry>()
    internal val attempts = linkedMapOf<DiagnosticExecutionClaim, Attempt>()
    internal var highWaterBytes = 0L

    @Synchronized
    fun retentionMeasurements(): QueryRetentionMeasurements =
        QueryRetentionMeasurements(
            QueryRetentionByteCount.measured(bytes()),
            QueryRetentionByteCount.measured(highWaterBytes),
            io.github.amichne.kast.query.contract.QueryCount.parse(entries.size + attempts.size).let {
                (it as Refinement.Refined).value
            },
        )

    @Synchronized
    internal fun admit(
        query: DiagnosticScopeQuery,
        token: ProtocolText?,
        limit: ProtocolCount,
        grant: RequestedExecutionBudget,
    ): DiagnosticCheckpointAdmission {
        expire()
        if (lifetime == Lifetime.RETIRED) return rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE)
        if (attempts.size >= capacity) return rejected(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED)
        val key = if (token == null) Key.First(DiagnosticReplayKey(query, limit, grant)) else Key.Continuation(token)
        val entry =
            when (val retained = acquireEntry(key, query)) {
                is Refinement.Refined -> retained.value
                is Refinement.Rejected -> return rejected(retained.failure)
            }
        when (val admitted = admitEntry(key, entry, query, limit)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return rejected(admitted.failure)
        }
        return claimEntry(key, entry, query, limit)
    }

    private fun acquireEntry(key: Key, query: DiagnosticScopeQuery): Refinement<Entry, DiagnosticCheckRejection> {
        entries[key]?.let {
            if (clock() - it.createdAt >= ttl) return unavailable()
            return Refinement.Refined(it)
        }
        if (key is Key.Continuation) return unavailable()
        val bytes = query.diagnosticRetainedIdentityBytes() + DIAGNOSTIC_ENTRY_OVERHEAD
        if (!makeCapacity(2, bytes + DIAGNOSTIC_ENTRY_OVERHEAD)) return diagnosticCapacityRejected()
        val entry =
            Entry(
                query,
                (key as Key.First).key.limit,
                Input.Scan(DiagnosticScanRequest.First(query)),
                bytes,
                clock(),
                null,
            )
        entries[key] = entry
        return Refinement.Refined(entry)
    }

    private fun admitEntry(
        key: Key,
        entry: Entry,
        query: DiagnosticScopeQuery,
        limit: ProtocolCount,
    ): Refinement<Unit, DiagnosticCheckRejection> {
        if (entry.owner != null) return unavailable()
        when (val admitted = diagnosticMatchingQuery(query, entry.query, limit, entry.limit)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
        if (entry.execution is Execution.Running)
            return Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_IN_USE)
        val published = entry.execution as? Execution.Published
        if (published != null && published.children.any { entries[it]?.owner != null || it !in entries })
            return unavailable()
        if (!makeCapacity(1, DIAGNOSTIC_ENTRY_OVERHEAD, setOf(key))) return diagnosticCapacityRejected()
        return Refinement.Refined(Unit)
    }

    private fun claimEntry(
        key: Key,
        entry: Entry,
        query: DiagnosticScopeQuery,
        limit: ProtocolCount,
    ): DiagnosticCheckpointAdmission {
        val claim = DiagnosticExecutionClaim()
        val published = entry.execution as? Execution.Published
        attempts[claim] = Attempt(key, query, limit, entry.createdAt, published?.page)
        if (published == null) entries[key] = entry.copy(execution = Execution.Running(claim))
        else attempts.getValue(claim).children.addAll(published.children)
        return when {
            published != null -> DiagnosticCheckpointAdmission.Outcome(published.page, claim)
            entry.input is Input.Output -> DiagnosticCheckpointAdmission.Outcome(entry.input.page, claim)
            entry.input is Input.Scan -> DiagnosticCheckpointAdmission.Execute(entry.input.request, claim)
            else -> rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
        }
    }

    @Synchronized
    internal fun stageScan(
        admission: DiagnosticCheckpointAdmission.Execute,
        result: DiagnosticScanResult,
    ): Refinement<DiagnosticStoredPage, DiagnosticCheckRejection> {
        val attempt = attempts[admission.claim] ?: return unavailable()
        if (!active(attempt)) return unavailable()
        val page =
            when (result) {
                is DiagnosticScanResult.Advancing -> result.page
                is DiagnosticScanResult.Complete -> result.page
                is DiagnosticScanResult.Qualified -> result.page
                is DiagnosticScanResult.Rejected ->
                    return Refinement.Refined(DiagnosticStoredPage(result, DiagnosticNextPage.Terminal))
            }
        if (page.facts.any { it.scope.lease != attempt.query.lease })
            return Refinement.Rejected(DiagnosticCheckRejection.STALE_CONTINUATION)
        if (result !is DiagnosticScanResult.Advancing)
            return Refinement.Refined(DiagnosticStoredPage(result, DiagnosticNextPage.Terminal))
        if (
            diagnosticMatchingQuery(result.checkpoint.query, attempt.query, attempt.limit, attempt.limit)
                is Refinement.Rejected
        )
            return Refinement.Rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
        if (result.checkpoint.retainedBytes < 0)
            return Refinement.Rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
        if (result.checkpoint.retainedBytes > maximumCheckpointBytes)
            return Refinement.Refined(DiagnosticStoredPage(result, DiagnosticNextPage.RetentionUnavailable))
        val bytes =
            result.checkpoint.retainedBytes +
                result.checkpoint.query.diagnosticRetainedIdentityBytes() +
                DIAGNOSTIC_ENTRY_OVERHEAD
        if (bytes > maximumCheckpointBytes || !makeCapacity(1, bytes))
            return Refinement.Refined(DiagnosticStoredPage(result, DiagnosticNextPage.RetentionUnavailable))
        val token =
            when (val issued = issueToken(SCAN_PREFIX)) {
                is Refinement.Refined -> issued.value
                is Refinement.Rejected -> return issued
            }
        val key = Key.Continuation(token)
        entries[key] =
            Entry(
                attempt.query,
                attempt.limit,
                Input.Scan(DiagnosticScanRequest.Resume(result.checkpoint)),
                bytes,
                attempt.createdAt,
                admission.claim,
            )
        attempt.children += key
        return Refinement.Refined(DiagnosticStoredPage(result, DiagnosticNextPage.Continue(token)))
    }

    /** The suffix is invisible until its parent's final fitted page commits under the same claim. */
    @Synchronized
    fun issueOutput(
        claim: DiagnosticExecutionClaim,
        page: DiagnosticPublishedPage,
        measuredBytes: QueryRetentionByteCount,
    ): Refinement<ProtocolText, DiagnosticCheckRejection> {
        val attempt = attempts[claim] ?: return unavailable()
        if (!active(attempt)) return unavailable()
        if (attempt.replay != null) return Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED)
        val detached = page.publicationPage()
        if (!detached.matches(attempt.query)) return Refinement.Rejected(DiagnosticCheckRejection.STALE_CONTINUATION)
        if (measuredBytes.value > maximumBytes / DIAGNOSTIC_CHARACTER_BYTES) return diagnosticCapacityRejected()
        val byteCount =
            detached.diagnosticRetainedBytes().coerceAtLeast(measuredBytes.value * DIAGNOSTIC_CHARACTER_BYTES) +
                attempt.query.diagnosticRetainedIdentityBytes() +
                DIAGNOSTIC_ENTRY_OVERHEAD
        val count = detached.factCount()
        val parentInput = entries[attempt.parent]?.input
        if (count == 0 || parentInput is Input.Output && count >= parentInput.page.factCount())
            return Refinement.Rejected(DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION)
        if (!makeCapacity(1, byteCount)) return diagnosticCapacityRejected()
        val token =
            when (val issued = issueToken(OUTPUT_PREFIX)) {
                is Refinement.Refined -> issued.value
                is Refinement.Rejected -> return issued
            }
        val key = Key.Continuation(token)
        entries[key] = Entry(attempt.query, attempt.limit, Input.Output(detached), byteCount, attempt.createdAt, claim)
        attempt.children += key
        return Refinement.Refined(token)
    }

    /** Commit is atomic: the immutable final page and every successor become visible together. */
    @Synchronized
    fun commit(
        claim: DiagnosticExecutionClaim,
        page: DiagnosticPublishedPage,
    ): Refinement<Unit, DiagnosticPublicationFailure> {
        val attempt =
            when (val admitted = admitPublicationAttempt(claim)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val parent =
            entries[attempt.parent] ?: return publicationRejected(DiagnosticPublicationFailure.DEPENDENCY_UNAVAILABLE)
        val final = page.publicationPage()
        if (!final.matches(attempt.query)) return publicationRejected(DiagnosticPublicationFailure.INVALID_FITTED_PAGE)
        if (attempt.replay != null) return commitReplay(claim, attempt, final)
        val referenced =
            when (val admitted = admitPublicationDependencies(claim, attempt, parent, final)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val bytes =
            final.diagnosticRetainedBytes() + parent.query.diagnosticRetainedIdentityBytes() + DIAGNOSTIC_ENTRY_OVERHEAD
        if (!makeCapacity(0, (bytes - parent.bytes).coerceAtLeast(0)))
            return publicationRejected(DiagnosticPublicationFailure.CAPACITY_EXCEEDED)
        entries.entries.removeIf { it.value.owner === claim && it.key !in referenced }
        entries[attempt.parent] =
            parent.copy(
                input = Input.Consumed,
                bytes = bytes,
                owner = null,
                execution = Execution.Published(final, referenced),
            )
        entries.replaceAll { _, value -> if (value.owner === claim) value.copy(owner = null) else value }
        attempts.remove(claim)
        return Refinement.Refined(Unit)
    }

    private fun admitPublicationAttempt(
        claim: DiagnosticExecutionClaim
    ): Refinement<Attempt, DiagnosticPublicationFailure> {
        if (lifetime == Lifetime.RETIRED) return publicationRejected(DiagnosticPublicationFailure.OWNER_RETIRED)
        val attempt = attempts[claim] ?: return publicationRejected(DiagnosticPublicationFailure.CLAIM_UNAVAILABLE)
        if (clock() - attempt.createdAt >= ttl) return publicationRejected(DiagnosticPublicationFailure.EXPIRED)
        return Refinement.Refined(attempt)
    }

    private fun commitReplay(
        claim: DiagnosticExecutionClaim,
        attempt: Attempt,
        final: DiagnosticPublishedPage,
    ): Refinement<Unit, DiagnosticPublicationFailure> {
        if (attempt.replay != final) return publicationRejected(DiagnosticPublicationFailure.PUBLISHED_PAGE_MISMATCH)
        if (attempt.children.any { it !in entries })
            return publicationRejected(DiagnosticPublicationFailure.DEPENDENCY_UNAVAILABLE)
        attempts.remove(claim)
        return Refinement.Refined(Unit)
    }

    private fun admitPublicationDependencies(
        claim: DiagnosticExecutionClaim,
        attempt: Attempt,
        parent: Entry,
        final: DiagnosticPublishedPage,
    ): Refinement<Set<Key>, DiagnosticPublicationFailure> {
        if ((parent.execution as? Execution.Running)?.claim !== claim)
            return publicationRejected(DiagnosticPublicationFailure.CLAIM_UNAVAILABLE)
        val selected = final.continuation()?.let { Key.Continuation(it) }
        if (selected == attempt.parent) return publicationRejected(DiagnosticPublicationFailure.NON_ADVANCING_SUCCESSOR)
        if (selected != null && selected !in attempt.children)
            return publicationRejected(DiagnosticPublicationFailure.DEPENDENCY_UNAVAILABLE)
        val referenced = reachable(selected)
        if (selected != null && referenced.isEmpty())
            return publicationRejected(DiagnosticPublicationFailure.DEPENDENCY_UNAVAILABLE)
        return Refinement.Refined(referenced)
    }

    @Synchronized
    fun discard(claim: DiagnosticExecutionClaim) {
        val attempt = attempts.remove(claim) ?: return
        entries.entries.removeIf { it.value.owner === claim }
        val parent = entries[attempt.parent]
        if ((parent?.execution as? Execution.Running)?.claim === claim) {
            if (attempt.parent is Key.First) entries.remove(attempt.parent)
            else entries[attempt.parent] = parent.copy(execution = Execution.Ready)
        }
        expire()
    }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.RETIRED
        entries.clear()
        attempts.clear()
    }

    private fun rejected(reason: DiagnosticCheckRejection) = DiagnosticCheckpointAdmission.Rejected(reason)

    private fun publicationRejected(reason: DiagnosticPublicationFailure) = Refinement.Rejected(reason)

    private fun unavailable() = Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE)

    companion object {
        const val SCAN_PREFIX = "diagnostic:v1:"
        const val OUTPUT_PREFIX = "diagnostic-output:v1:"
    }
}
