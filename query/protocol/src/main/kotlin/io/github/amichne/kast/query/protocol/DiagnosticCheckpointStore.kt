package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticScanCheckpoint
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanInventory
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanPage
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface DiagnosticNextPage {
    data object Terminal : DiagnosticNextPage

    data class Continue(val token: ProtocolText) : DiagnosticNextPage
}

internal data class DiagnosticStoredPage(val result: DiagnosticScanResult, val next: DiagnosticNextPage)

internal sealed interface DiagnosticCheckpointAdmission {
    data class Replay(val page: DiagnosticStoredPage) : DiagnosticCheckpointAdmission

    data class Execute(
        val request: DiagnosticScanRequest,
        val key: DiagnosticReplayKey,
        val createdAt: Long,
        val limit: ProtocolCount,
    ) : DiagnosticCheckpointAdmission

    data class Rejected(val reason: DiagnosticCheckRejection) : DiagnosticCheckpointAdmission
}

internal sealed interface DiagnosticReplayOrigin {
    data class First(val path: String, val lease: SemanticReadAuthority, val limit: ProtocolCount) :
        DiagnosticReplayOrigin

    data class Resume(val token: ProtocolText) : DiagnosticReplayOrigin
}

internal data class DiagnosticReplayKey(val origin: DiagnosticReplayOrigin, val grant: RequestedExecutionBudget)

/**
 * Diagnostic-owned retained progress and response replay, under the existing query retention policy. Every child
 * inherits the scan's creation time. All state is detached; no service or IDE object enters this store. Checkpoints and
 * replay payloads share this store's one combined entry/byte bound. Replay identifies caller/configured selections, not
 * the host's invocation-specific elapsed clamp. Each invocation projects its own actual admitted report.
 */
class DiagnosticCheckpointStore(
    private val capacity: Int = ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.defaultValue,
    private val maximumBytes: Long = ReadLimitParameter.QUERY_CONTINUATION_BYTES.defaultValue.toLong(),
    private val maximumCheckpointBytes: Long = ReadLimitParameter.QUERY_CHECKPOINT_BYTES.defaultValue.toLong(),
    ttlMillis: Long = ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS.defaultValue.toLong(),
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Checkpoint(val value: DiagnosticScanCheckpoint, val createdAt: Long, val limit: ProtocolCount) {
        val bytes = value.retainedBytes + value.query.retainedIdentityBytes() + ENTRY_OVERHEAD
    }

    private data class Replay(
        val query: DiagnosticScopeQuery,
        val limit: ProtocolCount,
        val key: DiagnosticReplayKey,
        val page: DiagnosticStoredPage,
        val createdAt: Long,
    ) {
        val bytes = page.retainedBytes() + query.retainedIdentityBytes() + key.retainedBytes() + ENTRY_OVERHEAD
    }

    private enum class Lifetime {
        ACTIVE,
        RETIRED,
    }

    private var lifetime = Lifetime.ACTIVE
    private val ttl = TimeUnit.MILLISECONDS.toNanos(ttlMillis)
    private val checkpoints = linkedMapOf<ProtocolText, Checkpoint>()
    private val replays = linkedMapOf<DiagnosticReplayKey, Replay>()

    @Synchronized
    internal fun admit(
        query: DiagnosticScopeQuery,
        token: ProtocolText?,
        limit: ProtocolCount,
        grant: RequestedExecutionBudget,
    ): DiagnosticCheckpointAdmission {
        expire()
        if (lifetime == Lifetime.RETIRED) return unavailable()
        val origin =
            if (token == null) DiagnosticReplayOrigin.First(query.path.toString(), query.lease, limit)
            else DiagnosticReplayOrigin.Resume(token)
        val key = DiagnosticReplayKey(origin, grant)
        when (val replay = lookupReplay(key, query, limit)) {
            ReplayLookup.Missing -> Unit
            is ReplayLookup.Retained -> return DiagnosticCheckpointAdmission.Replay(replay.page)
            is ReplayLookup.Rejected -> return DiagnosticCheckpointAdmission.Rejected(replay.reason)
        }
        if (token == null)
            return DiagnosticCheckpointAdmission.Execute(DiagnosticScanRequest.First(query), key, clock(), limit)
        val entry = checkpoints[token] ?: return unavailable()
        when (val admitted = matchingQuery(query, entry.value.query, limit, entry.limit)) {
            is Refinement.Rejected -> return DiagnosticCheckpointAdmission.Rejected(admitted.failure)
            is Refinement.Refined -> Unit
        }
        return DiagnosticCheckpointAdmission.Execute(
            DiagnosticScanRequest.Resume(entry.value),
            key,
            entry.createdAt,
            limit,
        )
    }

    @Synchronized
    internal fun publish(
        admission: DiagnosticCheckpointAdmission.Execute,
        result: DiagnosticScanResult,
    ): Refinement<DiagnosticStoredPage, DiagnosticCheckRejection> {
        expire()
        if (lifetime == Lifetime.RETIRED || clock() - admission.createdAt >= ttl)
            return Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE)
        when (val replay = lookupReplay(admission.key, admission.request.query, admission.limit)) {
            ReplayLookup.Missing -> Unit
            is ReplayLookup.Retained -> return Refinement.Refined(replay.page)
            is ReplayLookup.Rejected -> return Refinement.Rejected(replay.reason)
        }
        val next =
            when (val issued = nextPage(result)) {
                is Refinement.Refined -> issued.value
                is Refinement.Rejected -> return issued
            }
        val page = DiagnosticStoredPage(result, next)
        val replay = Replay(admission.request.query, admission.limit, admission.key, page, admission.createdAt)
        val extraBytes =
            replay.bytes +
                if (result is DiagnosticScanResult.Advancing)
                    result.checkpoint.retainedBytes + result.checkpoint.query.retainedIdentityBytes() + ENTRY_OVERHEAD
                else 0L
        val extraEntries = if (next is DiagnosticNextPage.Continue) 2 else 1
        if (extraEntries > capacity || extraBytes > maximumBytes) return capacityRejected()
        makeCapacity(extraEntries, extraBytes)
        if (next is DiagnosticNextPage.Continue && result is DiagnosticScanResult.Advancing) {
            checkpoints[next.token] = Checkpoint(result.checkpoint, admission.createdAt, admission.limit)
        }
        replays[admission.key] = replay
        return Refinement.Refined(page)
    }

    private sealed interface ReplayLookup {
        data object Missing : ReplayLookup

        data class Retained(val page: DiagnosticStoredPage) : ReplayLookup

        data class Rejected(val reason: DiagnosticCheckRejection) : ReplayLookup
    }

    private fun lookupReplay(
        key: DiagnosticReplayKey,
        query: DiagnosticScopeQuery,
        limit: ProtocolCount,
    ): ReplayLookup {
        val replay = replays[key] ?: return ReplayLookup.Missing
        when (val admitted = matchingQuery(query, replay.query, limit, replay.limit)) {
            is Refinement.Rejected -> return ReplayLookup.Rejected(admitted.failure)
            is Refinement.Refined -> Unit
        }
        val next = replay.page.next
        if (next !is DiagnosticNextPage.Continue || next.token in checkpoints) return ReplayLookup.Retained(replay.page)
        return when (key.origin) {
            is DiagnosticReplayOrigin.Resume -> ReplayLookup.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE)
            is DiagnosticReplayOrigin.First -> {
                // An orphaned replay cannot block a new tokenless read or revive an evicted continuation.
                replays.remove(key)
                ReplayLookup.Missing
            }
        }
    }

    private fun makeCapacity(extraEntries: Int, extraBytes: Long) {
        while (
            checkpoints.size + replays.size + extraEntries > capacity || retainedBytes() + extraBytes > maximumBytes
        ) {
            evictOldest()
        }
    }

    private fun nextPage(result: DiagnosticScanResult): Refinement<DiagnosticNextPage, DiagnosticCheckRejection> {
        if (result !is DiagnosticScanResult.Advancing) return Refinement.Refined(DiagnosticNextPage.Terminal)
        if (
            result.checkpoint.retainedBytes + result.checkpoint.query.retainedIdentityBytes() + ENTRY_OVERHEAD >
                maximumCheckpointBytes
        )
            return capacityRejected()
        val token =
            when (val parsed = ProtocolText.parse("diagnostic:v1:" + UUID.randomUUID())) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return capacityRejected()
            }
        if (token in checkpoints) return capacityRejected()
        return Refinement.Refined(DiagnosticNextPage.Continue(token))
    }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.RETIRED
        checkpoints.clear()
        replays.clear()
    }

    private fun retainedBytes(): Long = checkpoints.values.sumOf { it.bytes } + replays.values.sumOf { it.bytes }

    private fun expire() {
        val now = clock()
        checkpoints.entries.removeIf { now - it.value.createdAt >= ttl }
        replays.entries.removeIf { now - it.value.createdAt >= ttl }
    }

    private fun evictOldest() {
        val firstCheckpoint = checkpoints.entries.firstOrNull()
        val firstReplay = replays.entries.firstOrNull()
        if (
            firstCheckpoint != null &&
                (firstReplay == null || firstCheckpoint.value.createdAt <= firstReplay.value.createdAt)
        ) {
            checkpoints.remove(firstCheckpoint.key)
        } else if (firstReplay != null) replays.remove(firstReplay.key)
    }
}

private fun matchingQuery(
    current: DiagnosticScopeQuery,
    original: DiagnosticScopeQuery,
    currentLimit: ProtocolCount,
    originalLimit: ProtocolCount,
): Refinement<Unit, DiagnosticCheckRejection> =
    when {
        current.lease != original.lease -> Refinement.Rejected(DiagnosticCheckRejection.STALE_CONTINUATION)
        current.path != original.path || currentLimit != originalLimit ->
            Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH)
        else -> Refinement.Refined(Unit)
    }

private fun unavailable() = DiagnosticCheckpointAdmission.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE)

private fun capacityRejected(): Refinement.Rejected<DiagnosticCheckRejection> =
    Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED)

private fun DiagnosticStoredPage.retainedBytes(): Long =
    when (val value = result) {
        is DiagnosticScanResult.Advancing -> value.page.retainedBytes() + value.checkpoint.retainedBytes
        is DiagnosticScanResult.Complete -> value.page.retainedBytes()
        is DiagnosticScanResult.Qualified -> value.page.retainedBytes()
        is DiagnosticScanResult.Rejected -> ENTRY_OVERHEAD
    }

private fun DiagnosticScanPage.retainedBytes(): Long =
    ENTRY_OVERHEAD +
        facts.sumOf {
            FACT_OVERHEAD +
                (it.message.value.length.toLong() + it.code.value.length + it.location.file.value.length) *
                    CHARACTER_BYTES
        } +
        analyzedFiles.sumOf { ENTRY_OVERHEAD + it.value.length * CHARACTER_BYTES } +
        limitations.sumOf { ENTRY_OVERHEAD + it.file.value.length * CHARACTER_BYTES } +
        when (val inventory = inventory) {
            DiagnosticScanInventory.Enumerating -> 0L
            is DiagnosticScanInventory.Exhausted ->
                inventory.files.sumOf { ENTRY_OVERHEAD + it.value.length * CHARACTER_BYTES }
        }

private const val ENTRY_OVERHEAD = 512L
private const val FACT_OVERHEAD = 2048L
private const val CHARACTER_BYTES = 4L

private fun DiagnosticScopeQuery.retainedIdentityBytes(): Long =
    ENTRY_OVERHEAD + path.toString().length * CHARACTER_BYTES + lease.retainedIdentityBytes()

private fun SemanticReadAuthority.retainedIdentityBytes(): Long =
    ENTRY_OVERHEAD + (workspaceRoot.value.length.toLong() + identity.revisionKey.value.length) * CHARACTER_BYTES

private fun DiagnosticReplayKey.retainedBytes(): Long =
    ENTRY_OVERHEAD +
        when (val value = origin) {
            is DiagnosticReplayOrigin.First -> value.path.length * CHARACTER_BYTES + value.lease.retainedIdentityBytes()
            is DiagnosticReplayOrigin.Resume -> value.token.value.length * CHARACTER_BYTES
        }
