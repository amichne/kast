package io.github.amichne.kast.query.contract

/** Original semantic exhaustion is independent of the last presentation slice. */
sealed interface QueryInvestigationCompletion {
    data object NotEstablished : QueryInvestigationCompletion

    class Established private constructor(val original: QueryExecutionResult.Complete) : QueryInvestigationCompletion {
        companion object {
            fun from(original: QueryExecutionResult.Complete): QueryInvestigationCompletion =
                if (
                    original.result.rows is QueryRows.ValuePaths &&
                        original.coverage.resultCount.value == original.result.rows.values.size
                )
                    Established(original)
                else NotEstablished
        }
    }
}
