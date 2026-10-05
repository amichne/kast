package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation

/** Shared project-lifetime token receipts; stores charge and trim them under their admitted policy. */
class QueryContinuationRevocations {
    private val records = linkedMapOf<QueryExecutionContinuation, QueryContinuationFailure>()

    @Synchronized
    internal operator fun get(token: QueryExecutionContinuation): QueryContinuationFailure? = records[token]

    @Synchronized internal fun size(): Int = records.size

    @Synchronized
    internal fun remove(token: QueryExecutionContinuation) {
        records.remove(token)
    }

    @Synchronized
    internal fun record(token: QueryExecutionContinuation, cause: QueryContinuationFailure) {
        records[token] = cause
    }

    @Synchronized internal fun clear() = records.clear()

    @Synchronized
    internal fun discardOldest(): Boolean {
        val first = records.keys.firstOrNull() ?: return false
        records.remove(first)
        return true
    }
}
