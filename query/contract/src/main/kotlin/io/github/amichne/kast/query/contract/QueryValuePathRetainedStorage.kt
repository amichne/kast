package io.github.amichne.kast.query.contract

/** Shares request-owned graph accounting while charging retained evidence. */
internal fun valuePathRetainedBytes(result: QueryRetainedResult.ValuePaths, graph: QueryImpactRetainedGraph): Long =
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
        )
        .saturatedAdd(
            when (val witness = result.rows.accounting) {
                QueryValuePathAccounting.EvidenceOnly ->
                    result.rows.values.fold(0L) { bytes, path -> bytes.saturatedAdd(graph.path(path)) }
                is QueryValuePathAccounting.Investigated -> graph.ledger(witness.ledger)
            }
        )
