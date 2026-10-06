package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import kotlinx.serialization.json.Json

/** Conservative byte charges and bounded store measurements; these helpers allocate no retained identity. */
internal const val QUERY_PAGE_RESERVATION_MULTIPLIER = 4L
internal const val QUERY_PAGE_RESERVATION_OVERHEAD = 4096L

internal fun Long.saturatedAdd(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

internal fun Long.saturatedMultiply(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

internal fun QueryRunRequest.accountedRequestBytes(): Long =
    Json.encodeToString(QueryRunRequest.serializer(), this).toByteArray(Charsets.UTF_8).size.toLong() *
        RETAINED_ENCODING_MULTIPLIER

/** The store supplies its bounded totals; extraction cannot manufacture a semantic fact. */
internal fun queryRetentionMeasurements(
    retained: Long,
    highWater: Long,
    entryCount: Int,
    revocationCount: Int = 0,
): QueryRetentionMeasurements =
    QueryRetentionMeasurements(
        QueryRetentionByteCount.measured(retained),
        QueryRetentionByteCount.measured(highWater),
        (io.github.amichne.kast.query.contract.QueryCount.parse(entryCount) as Refinement.Refined).value,
        (io.github.amichne.kast.query.contract.QueryCount.parse(revocationCount) as Refinement.Refined).value,
    )

internal const val RETAINED_ENCODING_MULTIPLIER = 4L

internal fun QueryRunRequest.Run.accountedEntryBytes(payloadBytes: Long, capacity: Int, maximumBytes: Long): Long? {
    val requestBytes = accountedRequestBytes()
    if (capacity <= 0 || requestBytes !in 0..maximumBytes || payloadBytes !in 0..(maximumBytes - requestBytes))
        return null
    return requestBytes + payloadBytes
}
