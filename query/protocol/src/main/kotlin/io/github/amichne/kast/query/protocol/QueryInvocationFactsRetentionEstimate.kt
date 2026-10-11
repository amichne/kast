package io.github.amichne.kast.query.protocol

/**
 * Accepted facts only. Fixed aggregate bookkeeping stays within the admitted snapshot's existing 512-byte structure
 * allowance. Observation snapshots, capture temporaries and rejected staging are not retained by this owner.
 */
data class QueryInvocationFactsRetentionEstimate(
    val semantic: QueryRetentionByteCount,
    val preview: QueryRetentionByteCount,
    val rowReferenceCells: QueryRetentionByteCount,
) {
    val total: QueryRetentionByteCount =
        QueryRetentionByteCount.measured(semantic.value.saturatedAdd(preview.value).saturatedAdd(rowReferenceCells.value))

    operator fun plus(other: QueryInvocationFactsRetentionEstimate): QueryInvocationFactsRetentionEstimate =
        QueryInvocationFactsRetentionEstimate(
            QueryRetentionByteCount.measured(semantic.value.saturatedAdd(other.semantic.value)),
            QueryRetentionByteCount.measured(preview.value.saturatedAdd(other.preview.value)),
            QueryRetentionByteCount.measured(rowReferenceCells.value.saturatedAdd(other.rowReferenceCells.value)),
        )

    companion object {
        val Empty =
            QueryInvocationFactsRetentionEstimate(
                QueryRetentionByteCount.measured(0L),
                QueryRetentionByteCount.measured(0L),
                QueryRetentionByteCount.measured(0L),
            )
    }
}
