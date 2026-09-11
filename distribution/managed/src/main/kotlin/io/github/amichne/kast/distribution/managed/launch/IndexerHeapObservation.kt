package io.github.amichne.kast.distribution.managed.launch

import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded evidence emitted by the running JVM, never inferred from current caller configuration. */
class IndexerHeapObservation
private constructor(
    val requested: IndexerHeapSize,
    val observedMaximumBytes: Long,
) {
    fun boundaryDocument(): String = buildJsonObject {
        put("requestedMaxHeapMiB", requested.mebibytes)
        put("observedMaxHeapBytes", observedMaximumBytes)
    }
        .toString()

    companion object {
        fun observe(args: Array<String>, maximumBytes: Long): IndexerHeapObservationResult {
            val values = args.filter { it.startsWith("--max-heap-mib=") }.map { it.substringAfter('=') }
            if (values.size != 1)
                return IndexerHeapObservationResult.Rejected(IndexerHeapObservationFailure.ARGUMENT_CARDINALITY)
            val raw = values.single()
            val requested =
                when (val parsed = IndexerHeapSize.parse("${raw}m")) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected ->
                        return IndexerHeapObservationResult.Rejected(IndexerHeapObservationFailure.INVALID_ARGUMENT)
                }
            if (requested.mebibytes.toString() != raw)
                return IndexerHeapObservationResult.Rejected(IndexerHeapObservationFailure.INVALID_ARGUMENT)
            if (maximumBytes <= 0)
                return IndexerHeapObservationResult.Rejected(IndexerHeapObservationFailure.INVALID_OBSERVATION)
            return IndexerHeapObservationResult.Observed(IndexerHeapObservation(requested, maximumBytes))
        }
    }
}

enum class IndexerHeapObservationFailure {
    ARGUMENT_CARDINALITY,
    INVALID_ARGUMENT,
    INVALID_OBSERVATION,
}

sealed interface IndexerHeapObservationResult {
    data class Observed(val observation: IndexerHeapObservation) : IndexerHeapObservationResult

    data class Rejected(val failure: IndexerHeapObservationFailure) : IndexerHeapObservationResult
}
