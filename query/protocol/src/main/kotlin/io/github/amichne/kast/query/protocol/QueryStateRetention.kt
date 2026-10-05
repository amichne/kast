package io.github.amichne.kast.query.protocol

import java.util.UUID

/** Lifecycle operations on the single store's maps; the calling store owns every lock and effect. */
internal class QueryStateRetention(
    private val entries: MutableMap<QueryStateKey, QueryStateEntry>,
    private val transientClaims: MutableMap<UUID, QueryExecutionClaim>,
    private val capacity: Int,
    private val maximumBytes: Long,
    private val revocations: QueryContinuationRevocations,
) {
    fun revocation(key: QueryStateKey): QueryContinuationFailure? =
        when (key) {
            is QueryStateKey.Checkpoint -> revocations[key.token]
            is QueryStateKey.Output -> revocations[key.token]
            is QueryStateKey.Result -> null
        }

    /** Inspect before cleanup reclaims token identity; an absent bounded receipt proves no historical cause. */
    fun continuationFailure(key: QueryStateKey, now: Long, ttlMillis: Long): QueryContinuationFailure? {
        val entry = entries[key] as? QueryStateEntry.Producer ?: return revocation(key)
        if (!entry.withinLifetime(now, ttlMillis)) return QueryContinuationFailure.EXPIRED
        if (entry.owner != null) return null
        if (key !in entries.liveDependencyClosure(now, ttlMillis))
            return QueryContinuationFailure.DEPENDENCY_UNAVAILABLE
        return null
    }

    fun clear() {
        entries.clear()
        transientClaims.clear()
        revocations.clear()
    }

    fun retire(cause: QueryStateRetirement) {
        val revoked = entries.keys.toList()
        entries.clear()
        transientClaims.clear()
        for (key in revoked) remember(key, cause.failure)
    }

    fun revocationCount(): Int = revocations.size()

    fun evictFor(bytes: Long, protected: Set<QueryStateKey>): Boolean {
        while (entries.size + transientClaims.size >= capacity || retainedBytes() > maximumBytes - bytes) {
            if (entries.size + transientClaims.size < capacity && discardOldestRevocation()) continue
            val victim = entries.keys.firstOrNull { it !in protected && evictable(it) } ?: return false
            entries.remove(victim)
            remember(victim, QueryContinuationFailure.EVICTED)
        }
        return true
    }

    fun reserveFor(bytes: Long, protected: Set<QueryStateKey>): Boolean {
        while (retainedBytes() > maximumBytes - bytes) {
            if (discardOldestRevocation()) continue
            val victim = entries.keys.firstOrNull { it !in protected && evictable(it) } ?: return false
            entries.remove(victim)
            remember(victim, QueryContinuationFailure.EVICTED)
        }
        return true
    }

    private fun evictable(key: QueryStateKey): Boolean {
        val entry = entries[key] ?: return false
        if (
            entry.owner != null ||
                (entry as? QueryStateEntry.Producer)?.execution is QueryCheckpointExecution.Running ||
                attemptPins(key)
        )
            return false
        return entries.values.none { value ->
            key in value.dependencies()
        }
    }

    private fun attemptPins(key: QueryStateKey): Boolean =
        transientClaims.values.any { claim ->
            val roots =
                when (val origin = claim.origin) {
                    is QueryExecutionOrigin.Initial -> origin.inputs
                    is QueryExecutionOrigin.Replay -> origin.page.dependencies() + origin.token.key()
                    is QueryExecutionOrigin.Producer -> setOf(origin.token.key())
                }
            key in entries.dependencyClosure(roots)
        }

    fun retainedBytes(): Long {
        var total =
            revocations.size().toLong() * REVOCATION_RECEIPT_BYTES +
                transientClaims.values.sumOf { it.origin.accountedClaimBytes() }
        for (entry in entries.values) {
            if (entry.bytes > maximumBytes - total) return maximumBytes
            total += entry.bytes
        }
        return total
    }

    private fun discardOldestRevocation(): Boolean = revocations.discardOldest()

    fun trimRevocations() {
        while (revocations.size() > capacity.coerceAtLeast(0) || retainedBytes() > maximumBytes) {
            if (!discardOldestRevocation()) return
        }
    }

    /** Detached token and finite cause only. Receipts use the same byte quota and at most capacity slots. */
    fun remember(key: QueryStateKey, cause: QueryContinuationFailure) {
        val token =
            when (key) {
                is QueryStateKey.Result -> return
                is QueryStateKey.Checkpoint -> key.token
                is QueryStateKey.Output -> key.token
            }
        if (capacity <= 0 || maximumBytes < REVOCATION_RECEIPT_BYTES) return
        revocations.remove(token)
        while (revocations.size() >= capacity || retainedBytes() > maximumBytes - REVOCATION_RECEIPT_BYTES) {
            if (!discardOldestRevocation()) return
        }
        revocations.record(token, cause)
    }

    /** Only active claims pin physical storage beyond original dependency expiry. */
    fun expire(now: Long, ttlMillis: Long) {
        val active =
            entries
                .filter { (key, entry) ->
                    (entry as? QueryStateEntry.Producer)?.execution is QueryCheckpointExecution.Running ||
                        entry.owner != null ||
                        attemptPins(key)
                }
                .keys
        val retained = entries.liveDependencyClosure(now, ttlMillis) + entries.dependencyClosure(active)
        val removed = entries.filterKeys { it !in retained }
        entries.keys.removeAll(removed.keys)
        for ((key, entry) in removed) {
            remember(
                key,
                if (entry.withinLifetime(now, ttlMillis)) QueryContinuationFailure.DEPENDENCY_UNAVAILABLE
                else QueryContinuationFailure.EXPIRED,
            )
        }
    }
}

/** Accounting estimate for one opaque token identity and one closed cause; no retained source or row payload. */
internal const val REVOCATION_RECEIPT_BYTES = 256L
