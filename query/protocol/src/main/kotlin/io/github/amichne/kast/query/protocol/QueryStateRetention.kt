package io.github.amichne.kast.query.protocol

import java.util.UUID

/** Lifecycle operations on the single store's maps; the calling store owns every lock and effect. */
internal class QueryStateRetention(
    private val entries: MutableMap<QueryStateKey, QueryStateEntry>,
    private val transientClaims: Map<UUID, QueryExecutionClaim>,
    private val capacity: Int,
    private val maximumBytes: Long,
) {
    fun evictFor(bytes: Long, protected: Set<QueryStateKey>): Boolean {
        while (entries.size + transientClaims.size >= capacity || retainedBytes() > maximumBytes - bytes) {
            val victim = entries.keys.firstOrNull { it !in protected && evictable(it) } ?: return false
            entries.remove(victim)
        }
        return true
    }

    fun reserveFor(bytes: Long, protected: Set<QueryStateKey>): Boolean {
        while (retainedBytes() > maximumBytes - bytes) {
            val victim = entries.keys.firstOrNull { it !in protected && evictable(it) } ?: return false
            entries.remove(victim)
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
        var total = transientClaims.values.sumOf { it.origin.accountedClaimBytes() }
        for (entry in entries.values) {
            if (entry.bytes > maximumBytes - total) return maximumBytes
            total += entry.bytes
        }
        return total
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
        entries.keys.removeIf { it !in retained }
    }
}
