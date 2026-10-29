package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ReviewedCallbackReceiver

internal fun ReviewedCallbackReceiver.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        binding.storageBytes(g).saturatedAdd(declared.storageBytes(g)).saturatedAdd(g.text(type.value))
    }
