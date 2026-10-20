package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations

internal fun ValueKnownDeclarations.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.collection(declarations) { it.storageBytes(g) }
    }

internal fun RevalidatedRelationEndpoint.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) { endpoint.storageBytes(g) }
