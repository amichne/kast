package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.query.contract.QueryResult

internal data class QueryEvidencePresentation(val result: QueryResult, val window: QueryEvidenceWindowDocument) {
    companion object {
        fun create(
            result: QueryResult,
            start: QueryEvidenceCursor,
            maximumItems: ResultLimit,
        ): Refinement<QueryEvidencePresentation, QueryExecutionRejectionDocument> {
            val total =
                when (val count = QueryEvidenceCursor.parse(result.evidenceCount())) {
                    is Refinement.Refined -> count.value
                    is Refinement.Rejected ->
                        return rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
                }
            if (start.value > total.value) return rejected(QueryExecutionRejectionDocument.EVIDENCE_CURSOR_OUT_OF_RANGE)
            val end =
                QueryEvidenceCursor.parse(
                        minOf(start.value + minOf(MAX_PROTOCOL_ITEMS, maximumItems.value), total.value)
                    )
                    .required()
            val partition = Partition(start.value, end.value)
            val selected =
                result.copy(
                    failures = partition.select(result.failures),
                    omissions = partition.select(result.omissions),
                    walkObservations = partition.select(result.walkObservations),
                    referenceObservations = partition.select(result.referenceObservations),
                    discoveryObservations = partition.select(result.discoveryObservations),
                    relationObservations = partition.select(result.relationObservations),
                )
            return Refinement.Refined(
                QueryEvidencePresentation(selected, QueryEvidenceWindowDocument.create(start, end, total).required())
            )
        }

        private fun rejected(cause: QueryExecutionRejectionDocument) = Refinement.Rejected(cause)
    }
}

private class Partition(private val start: Int, private val end: Int) {
    private var offset = 0

    fun <T> select(values: List<T>): List<T> {
        val first = (start - offset).coerceIn(0, values.size)
        val last = (end - offset).coerceIn(first, values.size)
        offset += values.size
        return values.subList(first, last)
    }
}

internal fun QueryResult.evidenceCount(): Int =
    failures.size +
        omissions.size +
        walkObservations.size +
        referenceObservations.size +
        discoveryObservations.size +
        relationObservations.size
