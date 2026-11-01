package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ReviewedGroupBucket
import io.github.amichne.kast.relation.contract.ReviewedGroupCallbacks

internal fun ReviewedGroupBucket.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        callbacks
            .storageBytes(g)
            .saturatedAdd(g.node(sourceRange) { 0L })
            .saturatedAdd(sourceExecution.storageBytes(g))
            .saturatedAdd(callStack.storageBytes(g))
            .saturatedAdd(GROUP_PLACEMENT_SELECTION_BYTES)
    }

private fun ReviewedGroupCallbacks.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is ReviewedGroupCallbacks.OriginalElements -> selector.storageBytes(g)
            is ReviewedGroupCallbacks.TransformedElements ->
                selector.storageBytes(g).saturatedAdd(transform.storageBytes(g))
        }
    }

private const val GROUP_PLACEMENT_SELECTION_BYTES = 8L
