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
    fun continuationFailure(
        key: QueryStateKey,
        now: Long,
        ttlMillis: Long,
        invocation: QueryInvocationPublication? = null,
    ): QueryContinuationFailure? {
        val entry = entries[key] as? QueryStateEntry.Producer ?: return revocation(key)
        if (entry.ownedByRunner(invocation)) return null
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

    /** Private publication supplies allocation identity; normal dependency and active-claim pins still apply. */
    fun discardInvocation(key: QueryStateKey, invocation: QueryInvocationPublication) {
        if (invocationUnpinned(key, invocation)) entries.remove(key)
    }

    /** Transfer and release both require an exclusive lifetime; another owner may already depend on this key. */
    fun invocationUnpinned(key: QueryStateKey, invocation: QueryInvocationPublication): Boolean =
        entries[key]?.invocationOwner === invocation && evictable(key, invocation)

    private fun evictable(key: QueryStateKey, releasing: QueryInvocationPublication? = null): Boolean {
        val entry = entries[key] ?: return false
        val privateOwner = entry.privateRunnerOwner
        if (privateOwner != null && privateOwner !== releasing) return false
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
            total = total.saturatedAdd(entry.bytes)
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

    /** Active claims and private runner invocations pin storage beyond external token expiry. */
    fun expire(now: Long, ttlMillis: Long) {
        val active =
            entries
                .filter { (key, entry) ->
                    (entry as? QueryStateEntry.Producer)?.execution is QueryCheckpointExecution.Running ||
                        entry.owner != null ||
                        entry.privateRunnerOwner != null ||
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
