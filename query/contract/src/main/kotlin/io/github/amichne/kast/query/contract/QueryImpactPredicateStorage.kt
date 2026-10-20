package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ValueCallOccurrence
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal fun ValueCallOccurrence.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        is ValueInvocation -> storageBytes(g)
        is ValueFunctionInvocation -> storageBytes(g)
    }

internal fun ValueFunctionInvocation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        enclosing
            .storageBytes(g)
            .saturatedAdd(executionOwner.storageBytes(g))
            .saturatedAdd(signature.storageBytes(g))
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(g.node(basis) { 0L })
            .saturatedAdd(
                g.node(callable) {
                    g.text(callable.compilerIdentity.value).saturatedAdd(g.text(callable.moduleName.value))
                }
            )
            .saturatedAdd(
                g.node(identity) {
                    identity.owner
                        .storageBytes(g)
                        .saturatedAdd(identity.execution.storageBytes(g))
                        .saturatedAdd(g.node(identity.callable) { 0L })
                        .saturatedAdd(g.node(identity.basis) { 0L })
                        .saturatedAdd(g.node(identity.range) { 0L })
                }
            )
    }

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

internal fun ValueTransferEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is ValueSourceTransfer -> operation.storageBytes(g)
            is ValueTransferEvidence.Guarded -> delegate.storageBytes(g).saturatedAdd(added.storageBytes(g))
            ValueTransferEvidence.Direct -> 0L
            is ValueTransferEvidence.NormalBranchResult,
            is ValueTransferEvidence.StringInterpolation -> retainedBytes
        }
    }
