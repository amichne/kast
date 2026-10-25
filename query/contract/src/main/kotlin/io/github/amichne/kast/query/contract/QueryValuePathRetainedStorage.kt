package io.github.amichne.kast.query.contract

/** Keeps the standalone checkpoint component inside its measured, saturated total. */
class QueryValuePathRetainedStorage private constructor(val totalBytes: Long, val checkpointBytes: Long) {
    companion object {
        internal fun measure(
            result: QueryRetainedResult.ValuePaths,
            graph: QueryImpactRetainedGraph,
        ): QueryValuePathRetainedStorage {
            val checkpointBytes =
                (result.producerProgress as? QueryContinuationState.Resumable)?.checkpoint?.retainedBytes ?: 0L
            val total =
                retainedStorageBytes(
                        emptyList(),
                        result.failures,
                        result.omissions,
                        result.walkObservations,
                        result.coverage,
                        result.producerProgress,
                        result.lease,
                        result.referenceObservations,
                        result.discoveryObservations,
                        result.relationObservations,
                        checkpointBytes,
                    )
                    .saturatedAdd(
                        when (val witness = result.rows.accounting) {
                            QueryValuePathAccounting.EvidenceOnly ->
                                result.rows.values.fold(0L) { bytes, path -> bytes.saturatedAdd(graph.path(path)) }
                            is QueryValuePathAccounting.Investigated -> graph.ledger(witness.ledger)
                        }
                    )
            return QueryValuePathRetainedStorage(total, checkpointBytes)
        }
    }
}
