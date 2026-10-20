package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal fun ValuePredicates.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.collection(entries) { predicate ->
            g.node(predicate) {
                g.node(predicate.subject) { predicate.subject.occurrence.storageBytes(g) }
                    .saturatedAdd(
                        when (predicate) {
                            is ValuePredicate.Subtype -> predicate.resolvedType.storageBytes(g)
                            is ValuePredicate.NonNull -> 0L
                        }
                    )
            }
        }
    }

internal fun ValuePredicates.hasForeignBasis(lease: SemanticReadAuthority): Boolean = entries.any { predicate ->
    predicate.subject.occurrence.hasForeignBasis(lease) ||
        when (predicate) {
            is ValuePredicate.Subtype -> predicate.resolvedType.lease != lease
            is ValuePredicate.NonNull -> false
        }
}
